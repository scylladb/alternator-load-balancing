# Alternator - Client-side load balancing - Go

## Introduction

As explained in the [toplevel README](../../README.md), DynamoDB applications
are usually aware of a _single endpoint_, a single URL to which they
connect - e.g., `http://dynamodb.us-east-1.amazonaws.com`. But Alternator
is distributed over a cluster of nodes, and we would like the application to
send requests to all these nodes - not just to one. This is important for two
reasons: **high availability** (the failure of a single Alternator node should
not prevent the client from proceeding) and **load balancing** over all
Alternator nodes.

One of the ways to do this is to provide a modified library, which will
allow a mostly-unmodified application which is only aware of one
"endpoint URL" to send its requests to many different Alternator nodes.

Our intention is _not_ to fork the existing AWS client library for Go.
Rather, our intention is to provide a small library which tacks on to
the existing "aws-sdk-go" library which the application is already using,
and makes it do the right thing for Alternator.

## The library

The `AlternatorLB` class defined in `alternator_lb.go` can be used to
easily change any application using `aws-sdk-go` from using Amazon DynamoDB
to use Alternator: While DynamoDB only has one "endpoint", this class helps
us balance the requests between all the nodes in the Alternator cluster.

## Using the library

You create a regular `dynamodb.DynamoDB` client by one of the methods listed below and 
the rest of the application can use this dynamodb client normally
this `db` object is thread-safe and can be used from multiple threads.

This client will send requests to an Alternator nodes, instead of AWS DynamoDB.

Every request performed on patched session will pick a different live
Alternator node to send it to. Despite us sending different requests
to different nodes, Go will keep these connections cached and reuse them
when we send another request to the same node.

### Rack and Datacenter awareness

You can configure load balancer to target particular datacenter (region) or rack (availability zone) via `WithRack` and `WithDatacenter` options, like so:
```golang
    lb, err := alb.NewAlternatorLB([]string{"x.x.x.x"}, alb.WithRack("someRack"), alb.WithDatacenter("someDc1"))
```

Additionally, you can check if alternator cluster know targeted rack/datacenter:
```golang
	if err := lb.CheckIfRackAndDatacenterSetCorrectly(); err != nil {
		return fmt.Errorf("CheckIfRackAndDatacenterSetCorrectly() unexpectedly returned an error: %v", err)
	}
```

To check if cluster support datacenter/rack feature supported you can call `CheckIfRackDatacenterFeatureIsSupported`:
```golang
    supported, err := lb.CheckIfRackDatacenterFeatureIsSupported()
	if err != nil {
		return fmt.Errorf("failed to check if rack/dc feature is supported: %v", err)
	}
	if !supported {
        return fmt.Errorf("dc/rack feature is not supporte")	
    }
```

### Spawn `dynamodb.DynamoDB`

```golang
import (
	"fmt"
    alb "alternator_loadbalancing"

    "github.com/aws/aws-sdk-go/aws"
    "github.com/aws/aws-sdk-go/service/dynamodb"
)

func main() {
    lb, err := alb.NewAlternatorLB([]string{"x.x.x.x"}, alb.WithPort(9999))
    if err != nil {
        panic(fmt.Sprintf("Error creating alternator load balancer: %v", err))
    }
    ddb, err := lb.WithCredentials("whatever", "secret").NewDynamoDB()
    if err != nil {
        panic(fmt.Sprintf("Error creating dynamodb client: %v", err))
    }
    _, _ = ddb.DeleteTable(...)
}
```

## Decrypting TLS

Read wireshark wiki regarding decrypting TLS traffic: https://wiki.wireshark.org/TLS#using-the-pre-master-secret
In order to obtain pre master key secrets, you need to provide a file writer into `alb.WithKeyLogWriter`, example:

```go
	keyWriter, err := os.OpenFile("/tmp/pre-master-key.log", os.O_RDWR|os.O_CREATE|os.O_APPEND, 0666)
    if err != nil {
        panic("Error opening key writer: " + err.Error())
	}
	defer keyWriter.Close()
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithScheme("https"), alb.WithPort(httpsPort), alb.WithIgnoreServerCertificateError(true), alb.WithKeyLogWriter(keyWriter))
```

Then you need to configure your traffic analyzer to read pre master key secrets from this file.

## Example

You can find examples in `[alternator_lb_test.go](alternator_lb_test.go)`