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
"enpoint URL" to send its requests to many different Alternator nodes.

Our intention is _not_ to fork the existing AWS client library for Python -
**boto3**. Rather, our intention is to provide a small library which tacks
on to any version of boto3 that the application is already using, and makes
boto3 do the right thing for Alternator.

## The `boto3_alternator` library
Use `import boto3_alternator` to make any unmodified boto3 client use
multiple nodes of an Alternator cluster instead of just one (which you'll
normally get if providing just one node's IP address as the endpoint URL).

This library provides two alternative modes:
1. Every time that boto3 needs to open a connection to DynamoDB,
   it will pick a different live Alternator node. When a connection
   breaks and a new connection needs to be opened, it will pick a
   destination again.
2. Every time that boto3 sends a *request* to DynamoDB, it will send it
   to a different live Alternator node. Two consecutive requests will
   often go to different nodes. Connections may be reused, however, if
   a large number of requests are sent from the same thread (boto3
   "resource") - botocore's "connection pool" feature is responsible for
   this reuse, and is limited by botocore's connection pool size.

The first mode is best when an application has many threads or boto3
"resources" - as each of those opens a different connection. The second
mode is better when the application only has one or very few threads or
boto3 "resources". The second mode sends each request to a different
Alternator node, instead of sending all these requests over an already
established connection, so this mode is contraindicated when the client
has many threads or boto3 "resources" because each of these resources,
instead of keeping one open connection, will keep many (the size
of botocore's "connection pool") to many different Alternator nodes.

Both modes start an additional thread which periodically updates the
list of live Alternator nodes by contacting one of the known nodes and
asking it for a list of the rest (in this data-center).

Both modes work by "monkey-patching", or instrumenting, different
library functions:
1. The first mode monkey-patches `socket.getaddrinfo`, so every time
   boto3 uses it to open a new connection, it will pick a different node.
2. The second mode monkey-patches botocore's request-sending function,
   `botocore.httpsession.URLLib3Session.send`, so that each request
   is sent to a different node.

## Using the library
To use this library, import it and then run the `setup1()` or `setup2()`
function to pick between the two different modes described above:

```python
import boto3_alternator

alternator_url = boto3_alternator_3.setup1(
    # A list of known Alternator nodes. One of them must be responsive.
    ['127.0.0.1'],
    # Alternator scheme (http or https) and port
   'http', 8000,
    # A "fake" domain name which, if used by the application, will be
    # resolved to one of the Scylla nodes.
    'dog.scylla.com')

# The setup function returns the "endpoint URL", in this example it will be
# "http://dog.scylla.com:8000/". You will need to use this endpoint URL to
# create the boto3 resource, e.g.,
dynamodb = boto3.resource('dynamodb', endpoint_url=alternator_url,
               aws_access_key_id='???', aws_secret_access_key='???')
```

The "fake domain", in this example, `dog.scylla.com`, can be any domain name,
it doesn't need to exist - and in fact, it probably shouldn't. After
`setup1()` or `setup2()` is run, attempts to use this domain name in boto3
are captured and forwarded to one of the live Alternator nodes - so the
real DNS is never asked about `dog.scylla.com`.

## Examples

This directory also contains two trivial examples of using `boto3_alternator`,
(try1.py) and (try2.py):
* `try1.py` demonstrates `setup1()`. It first demonstrates that we if we open
   five different boto3 "resources" and send a request `describe_endpoints()`
   to each one, they will arrive at different Alternator nodes.
   Then, this example makes 100 consecutive requests, with a 2 second
   delay between requests. Because all these requests use the same
   boto3 "resource", the `setup1()` mode means that all requests are sent
   to the same node. However, `boto3_alternator` still guarantees high
   availability: if during this long loop one kills this chosen Alternator
   node, `boto3_alternator` will automatically notice this, will create a
   connection to some other Alternator node, and requests will continued to
   succeed.
* `try2.py` demonstrates `setup2()`. It creates just one boto3 "resource",
   and then makes ten requests to it - which can be seen are each sent to
   a different Alternator node.

Both examples initialize the library assuming that one Alternator node is
available at `http://127.0.0.1:8000`, from which `boto3_alternator` 
will discover the rest. You can edit the examples to point them to
Alternator running elsewhere.
