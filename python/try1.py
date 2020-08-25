#!/usr/bin/python
import boto3
import boto3_alternator

import time

alternator_url = boto3_alternator.setup1(
    # A list of known Alternator nodes. One of them must be responsive.
    ['127.0.0.1'],
    # Alternator scheme (http or https) and port
    'http', 8000,
    # A "fake" domain name which, if used by the application, will be
    # resolved to one of the Scylla nodes.
    'dog.scylla.com')

print("Load balancing test:")
# Open several different "resources", so each one will connect to
# a different node.
for i in range(5):
    dynamodb = boto3.resource('dynamodb', endpoint_url=alternator_url,
            aws_access_key_id='alternator', aws_secret_access_key='secret_pass')
    print(dynamodb.meta.client.describe_endpoints()['Endpoints'][0]['Address'])
    #time.sleep(1)

print("High-availability test:")
# A test for high-availability: We open a single resource, and make
# 100 requests to it. This is the setup1() implementation, so all the
# requests go to the same node. However, if this node is killed, a
# new connection will be established to another node. You can try this
# by killing the connected node in the middle of this test.
dynamodb = boto3.resource('dynamodb', endpoint_url=alternator_url,
    aws_access_key_id='alternator', aws_secret_access_key='secret_pass')
for i in range(100):
    print(dynamodb.meta.client.describe_endpoints()['Endpoints'][0]['Address'])
    time.sleep(2)
