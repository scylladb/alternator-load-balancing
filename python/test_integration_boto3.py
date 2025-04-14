from unittest.mock import patch

import urllib3.connection


from alternator_lb import AlternatorLB, Config


class TestAlternatorBotocore:
    initial_nodes = ['172.43.0.2']
    http_port = 9998
    https_port = 9999

    def test_check_if_rack_datacenter_feature_is_supported(self):
        lb = AlternatorLB(Config(nodes=self.initial_nodes,
                          port=self.http_port, datacenter="fake_dc"))
        lb.check_if_rack_datacenter_feature_is_supported()

    def test_check_if_rack_and_datacenter_set_correctly_wrong_dc(self):
        lb = AlternatorLB(Config(nodes=self.initial_nodes,
                          port=self.http_port, datacenter="fake_dc"))
        try:
            lb.check_if_rack_and_datacenter_set_correctly()
            assert False, "Expected ValueError"
        except ValueError:
            pass

    def test_http_connection_persistent(self):
        self._test_connection_persistent("http", 1)
        self._test_connection_persistent("http", 2)

    def test_https_connection_persistent(self):
        self._test_connection_persistent("https", 1)
        self._test_connection_persistent("https", 2)

    def _test_connection_persistent(self, schema: str, max_pool_connections: int):
        cnt = 0
        if schema == "http":
            original_init = urllib3.connection.HTTPConnection.__init__
        else:
            original_init = urllib3.connection.HTTPSConnection.__init__

        def wrapper(self, *args, **kwargs):
            nonlocal cnt
            nonlocal original_init
            cnt += 1
            return original_init(self, *args, **kwargs)

        if schema == "http":
            patched = patch.object(
                urllib3.connection.HTTPConnection, '__init__', new=wrapper)
        else:
            patched = patch.object(
                urllib3.connection.HTTPSConnection, '__init__', new=wrapper)

        with patched:
            lb = AlternatorLB(Config(
                schema=schema,
                nodes=self.initial_nodes,
                port=self.http_port if schema == "http" else self.https_port,
                datacenter="fake_dc",
                update_interval=0,
                max_pool_connections=max_pool_connections,
            ))

            dynamodb = lb.new_boto3_dynamodb_client()
            try:
                dynamodb.delete_table(TableName="FakeTable")
            except Exception as e:
                if e.__class__.__name__ != "ResourceNotFoundException":
                    raise
            assert cnt == 1
            try:
                dynamodb.delete_table(TableName="FakeTable")
            except Exception as e:
                if e.__class__.__name__ != "ResourceNotFoundException":
                    raise
            assert cnt == 1  # Connection should be carried over to another request

            lb._update_live_nodes()
            assert cnt == 2  # AlternatorLB uses different connection pool, so one more connection will be created
            lb._update_live_nodes()
            assert cnt == 2  # And it should be carried over to another attempt of pulling nodes

    def test_check_if_rack_and_datacenter_set_correctly_correct_dc(self):
        lb = AlternatorLB(Config(nodes=self.initial_nodes,
                          port=self.http_port, datacenter="datacenter1"))
        lb.check_if_rack_and_datacenter_set_correctly()

    @staticmethod
    def _run_create_add_delete_test(dynamodb):
        TABLE_NAME = "TestTable"
        ITEM_KEY = {'UserID': {'S': '123'}}

        try:
            dynamodb.delete_table(TableName=TABLE_NAME)
        except Exception as e:
            if e.__class__.__name__ != "ResourceNotFoundException":
                raise

        print("Creating table...")
        dynamodb.create_table(
            TableName=TABLE_NAME,
            KeySchema=[{'AttributeName': 'UserID',
                        'KeyType': 'HASH'}],  # Primary Key
            AttributeDefinitions=[
                {'AttributeName': 'UserID', 'AttributeType': 'S'}],  # String Key
            ProvisionedThroughput={
                'ReadCapacityUnits': 5, 'WriteCapacityUnits': 5}
        )
        print(f"Table '{TABLE_NAME}' creation started.")

        # 2️⃣ Add an Item
        print("Adding item to the table...")
        dynamodb.put_item(
            TableName=TABLE_NAME,
            Item={
                'UserID': {'S': '123'},
                'Name': {'S': 'Alice'},
                'Age': {'N': '25'}
            }
        )
        print("Item added.")

        # 3️⃣ Get the Item
        print("Retrieving item...")
        response = dynamodb.get_item(TableName=TABLE_NAME, Key=ITEM_KEY)
        if 'Item' in response:
            print("Retrieved Item:", response['Item'])
        else:
            print("Item not found.")

        # 4️⃣ Delete the Item
        print("Deleting item...")
        dynamodb.delete_item(TableName=TABLE_NAME, Key=ITEM_KEY)

    def test_botocore_create_add_delete(self):
        lb = AlternatorLB(Config(
            nodes=self.initial_nodes,
            port=self.http_port,
            datacenter="datacenter1",
        ))
        self._run_create_add_delete_test(lb.new_botocore_dynamodb_client())

    def test_boto3_create_add_delete(self):
        lb = AlternatorLB(Config(
            nodes=self.initial_nodes,
            port=self.http_port,
            datacenter="datacenter1",
        ))
        self._run_create_add_delete_test(lb.new_boto3_dynamodb_client())
