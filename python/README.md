# Alternator - Client-side load balancing - Python

## Introduction
As explained in the [toplevel README](../README.md), DynamoDB applications
are usually aware of a _single endpoint_, a single URL to which they
connect - e.g., `http://dynamodb.us-east-1.amazonaws.com`. But Alternator
is distributed over a cluster of nodes and we would like the application to
send requests to all these nodes - not just to one. This is important for two
reasons: **high availability** (the failure of a single Alternator node should
not prevent the client from proceeding) and **load balancing** over all
Alternator nodes.

One of the ways to do this is to provide a modified library, which will
allow a mostly-unmodified application which is only aware of one
"endpoint URL" to send its requests to many different Alternator nodes.

Our intention is _not_ to fork the existing AWS client library for Python -
**boto3**. Rather, our intention is to provide a small library which tacks
on to any version of boto3 that the application is already using, and makes
boto3 do the right thing for Alternator.

## The `alternator_lb` library
Use `import alternator_lb` to make any boto3 client use Alternator cluster balancing load between nodes.
This library periodically syncs list of active nodes with the cluster.

## Using the library

### Create new dynamodb botocore client

```python
from alternator_lb import AlternatorLB, Config

lb = AlternatorLB(Config(nodes=['x.x.x.x'], port=9999))
dynamodb = lb.new_botocore_dynamodb_client()

dynamodb.delete_table(TableName="SomeTable")
```

### Create new dynamodb boto3 client

```python
from alternator_lb import AlternatorLB, Config

lb = AlternatorLB(Config(nodes=['x.x.x.x'], port=9999))
dynamodb = lb.new_boto3_dynamodb_client()

dynamodb.delete_table(TableName="SomeTable")
```

### Rack and Datacenter awareness

You can make it target nodes of particular datacenter or rack, as such:
```python
    lb = alternator_lb.AlternatorLB(['x.x.x.x'], port=9999, datacenter='dc1', rack='rack1')
```

You can also check if cluster knows datacenter and/or rack you are targeting:
```python
    try:
        lb.check_if_rack_and_datacenter_set_correctly()
    except ValueError:
        raise RuntimeError("Not supported")
```

This feature requires server support, you can check if server supports this feature:
```python
    try:
        supported = lb.check_if_rack_datacenter_feature_is_supported()
    except RuntimeError:
        raise RuntimeError("failed to check")
```

## Examples

Find more examples in `alternator_lb_tests.py`