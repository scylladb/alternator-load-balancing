#!/usr/bin/python
import boto3
import boto3_alternator

alternator_url = boto3_alternator.setup2(
    # A list of known Alternator nodes. One of them must be responsive.
    ['127.0.0.1'],
    # Alternator scheme (http or https) and port
    'http', 8000,
    # A "fake" domain name which, if used by the application, will be
    # resolved to one of the Scylla nodes.
    'dog.scylla.com')

# Load balancing test: create just one resource, and see how each request
# gets sent to a different node.
dynamodb = boto3.resource('dynamodb', endpoint_url=alternator_url,
        aws_access_key_id='alternator', aws_secret_access_key='secret_pass')
for i in range(10):
    print(dynamodb.meta.client.describe_endpoints()['Endpoints'][0]['Address'])
