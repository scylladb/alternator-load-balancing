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

## The `alternatorlb` library

The Go integration exists in two flavors. The [v1](v1) directory contains
a snippet for integrating Alternator with the `aws-sdk-go-v1`, while the
[v2](v2) directory contains a Go module, that can be used with
the `aws-sdk-go-v2`. See the README.md of these respective directories for more
details on how to use them.
