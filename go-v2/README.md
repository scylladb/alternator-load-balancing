# Alternator - Client-side load balancing - Go

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

Our intention is _not_ to fork the existing AWS client library for Go.
Rather, our intention is to provide a small library which tacks on to
the existing "aws-sdk-go" library which the application is already using,
and makes it do the right thing for Alternator.

## The `alternator_lb.go` library
The `AlternatorNodes` class defined in `alternator_lb.go` can be used to
easily change any application using `aws-sdk-go-v2` from using Amazon DynamoDB
to use Alternator: While DynamoDB only has one "endpoint", this class helps
us balance the requests between all the nodes in the Alternator cluster.
 
## Using the library
To use this class, simply replace the code which creates a AWS SDK
`aws.Config`
Instead of the usual way of creating a `Config`, like:
```golang
cfg, err := config.LoadDefaultConfig(ctx)
```
Use an `AlternatorNodes` object, which keeps track of the live Alternator
nodes, to create a session with the following commands:
```golang
alternatorNodes := alternatorlb.NewAlternatorNodes("http", 8000, "127.0.0.1")
alternatorNodes.Start(ctx, 1*time.Second)
defer alternatorNodes.Stop()

cfg := alternatorNodes.Config("dog.scylladb.com", "alternator", "secret_pass")
```
Then, the rest of the applicaton can use this config normally - call
db := dynamodb.NewFromConfig(cfg)` and then send DynamoDB requests to db; As
usual, this `db` object is thread-safe and can be used from multiple
threads.

The parameters to `NewAlternatorNodes()` indicate a list of known
Alternator nodes, and their common scheme (http or https) and port.
This list can contain one or more nodes - we then periodically contact
 these nodes to fetch the full list of nodes using Alternator's
`/localnodes` request. In the `Config()` method, one needs to pick a
"fake domain" which doesn't really mean anything (except it will be used as
the Host header, and be returned by the DescribeEndpoints request), and
the key and secret key for authentication to Alternator.

Every request performed on this new session will pick a different live
Alternator node to send it to. Despite us sending different requests
 to different nodes, Go will keep these connections cached and reuse them
when we send another request to the same node.

(TODO: figure out the limitations of this caching. Where is it documented?).

## Example

This directory also contains one trivial example of using `alternator_lb.go`,
[example.go](example.go). This example opens a config using `NewAlternatorNodes()`, as
described above, and then uses it 20 times in a loop - and we'll see
that every request will be sent to a different node.
