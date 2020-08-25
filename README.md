# Alternator Load Balancing

This repository contains a collection of source code and scripts that can
be used to implement **load balancing** for a Scylla Alternator cluster.

All code in this repository is open source, licensed under the
[Apache License 2.0](LICENSE).

## Introduction

**[Scylla](https://github.com/scylladb/scylla)** is an open-source distributed
database.  **[Alternator](https://docs.scylladb.com/using-scylla/alternator/)**
is a Scylla feature which adds Amazon DynamoDB&trade; compatibility to
Scylla. With Alternator, Scylla is fully (or [almost fully](https://github.com/scylladb/scylla/blob/master/docs/alternator/alternator.md#current-compatibility-with-dynamodb))
compatible with DynamoDB's HTTP and JSON based API. Unmodified applications
written with any of Amazon's [SDK libraries](https://aws.amazon.com/tools/)
can connect to a Scylla Alternator cluster instead of to Amazon's DynamoDB.

However, there is still one fundamental difference between how DynamoDB
and a Scylla cluster appear to an application:
  * The entire DynamoDB service is presented to the application as a
    **single endpoint**, for example
    `http://dynamodb.us-east-1.amazonaws.com`.
  * Scylla is not a single endpoint - it is a _distributed_ database - a
    cluster of **many nodes**.

If we configure the application to use just one of the Scylla nodes as the
single endpoint, this specific node will become a performance bottleneck
as it gets more work than the other nodes. Moreover, this node will become
a single point of failure - if it fails, the entire service is unavailable.

So what Alternator users need now is a way for a DynamoDB application - which
was written with just a single endpoint in mind - to send requests to all of
Alternator's nodes, not just to one. The mechanisms we are looking for should
equally load all of Alternator's nodes (_load balancing_) and ensure that the
service continues normally even if some of these nodes go down (_high
availability_).

The goal of this repository is to offer Alternator users with such
load balancing mechanisms, in the form of code examples, libraries,
and documentation.

## This repository

The most obvious load-balancing solution is a _load balancer_, a machine
or a virtual service which sits in front of the Alternator cluster and
forwards the HTTP requests that it gets to the different Alternator nodes.
This is a good option for some setups, but a costly one because all the
request traffic needs to flow through the load balancer.

In [this document](https://docs.google.com/document/d/1twgrs6IM1B10BswMBUNqm7bwu5HCm47LOYE-Hdhuu_8/) we surveyed some additional **server-side**
load-balancing mechanisms besides the TCP or HTTP load balancer.
These including _DNS_, _virtual IP addresses_, and _coordinator-only nodes_.
In the [dns](dns) subdirectory in this repository we demonstrate a simple
proof-of-concept of the DNS mechanism.

But the bulk of this repository is devoted to **client-side** load balancing.
In client-side load balancing, the client is modified to connect to all
Alternator nodes instead of just one. Client-side load balancing simplifies
server deployment and lowers server costs - as we do not need to deploy
additional server-side nodes or services.

Of course, our goal is to require _as little as possible_ changes to the
client. Ideally, all that would need to be changed in an application is to
have it load an additional library, or initialize the existing library a bit
differently; From there on, the usual unmodified AWS SDK functions will
automatically use all of Alternator's nodes instead of just one.

We currently provide libraries to do exactly that in four programming
languages: [go](go), [java](java), [javascript](javascript) (node.js), and
[python](python). Each of these directories includes a README file
explaining how to use this library in an application. These libraries are not
complete DynamoDB drivers - the application continues to use Amazon's
SDKs (e.g., boto3 in Python). Rather, what our libraries do is to
automatically retrieve the list of nodes in an Alternator cluster, and
configure or trick the Amazon SDK into sending requests to many different
nodes instead of always to the same one.
