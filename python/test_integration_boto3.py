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

    def test_check_if_rack_and_datacenter_set_correctly_correct_dc(self):
        lb = AlternatorLB(Config(nodes=self.initial_nodes,
                          port=self.http_port, datacenter="datacenter1"))
        lb.check_if_rack_and_datacenter_set_correctly()

    def _run_create_add_delete_test(self, dynamodb):
        lb = AlternatorLB(
            Config(nodes=self.initial_nodes, port=self.http_port))
        lb.patch_dynamodb_client(dynamodb)

        TABLE_NAME = "TestTable"
        ITEM_KEY = {'UserID': {'S': '123'}}

        try:
            dynamodb.delete_table(TableName=TABLE_NAME)
        except Exception:
            pass

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
        import botocore.session

        # Create a DynamoDB client
        self._run_create_add_delete_test(botocore.session.get_session(
        ).create_client('dynamodb', region_name='us-east-1', aws_access_key_id="fake-key-id", aws_secret_access_key="fake-key"))

    def test_boto3_create_add_delete(self):
        import boto3

        self._run_create_add_delete_test(
            boto3.client('dynamodb', region_name='us-east-1', aws_access_key_id="fake-key-id", aws_secret_access_key="fake-key"))
