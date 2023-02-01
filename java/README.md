# Alternator - Client-side load balancing - Java

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

Our intention is _not_ to fork the existing AWS client library (SDK) for Java.
Rather, our intention is to provide a tiny library which tacks on to any
version of the AWS SDK that the application is already using, and makes
it do the right thing for Alternator.

AWS SDK for Java has two distinct versions: Version 1 and Version 2.
Version 2 is a complete rewrite of Version 1, with a completely different
API. It was released in 2017, and announced in the following post:
https://aws.amazon.com/blogs/developer/aws-sdk-for-java-2-0-developer-preview/
However, although Amazon recommend version 2 for new applications, both
versions are still in popular use today, so the Alternator load balancing
library described here supports both.

## The LoadBalancing jar
To build a jar of the Alternator client-side load balancer, use
```
mvn package
```
Which creates `target/LoadBalancing-1.0.jar`.

As explained above, this jar does not _replace_ the AWS SDK for Java, but
accompanies it, and either version 1 or 2 of the AWS SDK for Java can be
used (the details on how to use are slightly different for each version,
so will be explained in separate sections below).

As we show below, the jar provides a new mechanism to configure a
DynamoDB (v1) or DynamoDbClient (v2) object, which the application
can then use normally using the standard AWS SDK for Java, to make
requests. The load balancer library ensures that each of these requests
goes to a different live Alternator node.

The load balancer library is also responsible for _discovering_ which
Alternator nodes exist, and maintaining this list as the Alternator cluster
changes. It does this using an additional background thread, which
periodically polls one of the known nodes, asking it for a list of all other
nodes (in this data-center).

## Using the library, in AWS SDK for Java v1

An application using AWS SDK for Java v1 creates a `DynamoDB` object and then
uses it to perform various requests. Traditionally, to create such an object,
an application that wishes to connect to a specific URL would use code that
looks something like this:

```java
    AWSCredentialsProvider myCredentials =
        new AWSStaticCredentialsProvider(new BasicAWSCredentials("myusername", "mypassword"));

    AmazonDynamoDB client = AmazonDynamoDBClientBuilder.standard()
        .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(
            "https://127.0.0.1:8043/", "region-doesnt-matter"))
        .withCredentials(myCredentials)
        .build();
    DynamoDB dynamodb = new DynamoDB(client);
```

To use the Alternator load balancer and all Alternator nodes, all you need to
do is tell the DynamoDB client to use a special request handler. The new code
will look like this:

```java
    import com.scylladb.alternator.AlternatorRequestHandler;

    URI uri = URI.create("https://127.0.0.1:8043/");
    AlternatorRequestHandler handler = new AlternatorRequestHandler(uri);
    AmazonDynamoDB client = AmazonDynamoDBClientBuilder.standard()
        .withRegion("region-doesnt-matter")
        .withRequestHandlers(handler)
        .withCredentials(myCredentials)
        .build();
    DynamoDB dynamodb = new DynamoDB(client);
```

The application can then use this `DynamoDB` object completely normally, just
that each request will go to a different Alternator node, instead of all of
them going to the same URL.

The main novelty in the new code above is the `AlternatorRequestHandler`,
which is passed into the client builder with `withRequestHandlers(handler)`.
The application should keep just one `AlternatorRequestHandler` object, as it
keeps a background thread maintaining the list of live Alternator nodes, and
it is pointless to have more than one of these threads.

The parameter `uri` one known Alternator node, which is then contacted to
discover the rest. After this initialization, this original node may go down
at any time - any other already-known node can be used to retrieve the node
list, and we no longer rely on the original node.

The region passed to `withRegion` does not matter (and can be any string),
because `AlternatorRequestHandler` will override the chosen endpoint anyway,
Unfortunately we can't just drop the `withRegion()` call, because without
it the library will expect to find a default region in the configuration file
and complain when it is missing.

You can see `src/test/java/com/scylladb/alternator/test/Demo1.java` for a
complete example of using client-side load balancing with AWS SDK for Java v1.
After building with `mvn package`, you can run this demo with the command:
```
mvn exec:java -Dexec.mainClass=com.scylladb.alternator.test.Demo1 -Dexec.classpathScope=test
```

## Using the library, in AWS SDK for Java v2

An application using AWS SDK for Java v2 creates a `DynamoDbClient` object
and then uses it to perform various requests. Traditionally, to create such
an object, an application that wishes to connect to a specific URL would use
code that looks something like this:

```java
    static AwsCredentialsProvider myCredentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create("myuser", "mypassword"));
    URI uri = URI.create("https://127.0.0.1:8043");
    DynamoDbClient client = DynamoDbClient.builder()
        .endpointOverride(url)
        .credentialsProvider(myCredentials)
        .build();
```

To use the Alternator load balancer and all Alternator nodes, you need to use
`AlternatorClient` instead of `DynamoDbClient` to build the client.
The new code will look like this:

```java
    import com.scylladb.alternator.AlternatorClient;

    URI uri = URI.create("https://127.0.0.1:8043");
    DynamoDbClient client = AlternatorClient.builder(uri.getScheme(), uri.getHost(), uri.getPort())
        .credentialsProvider(myCredentials)
        .build();
```

The application can then use this `DynamoDBClient` object completely normally,
just that each request will go to a different Alternator node, instead of all
of them going to the same URL.

The parameter `uri` is one known Alternator node, which is then contacted to
discover the rest. After this initialization, this original node may go down
at any time - any other already-known node can be used to retrieve the node
list, and we no longer rely on the original node.

You can see `src/test/java/com/scylladb/alternator/test/Demo2.java` for a
complete example of using client-side load balancing with AWS SDK for Java v2.
After building with `mvn package`, you can run this demo with the command:
```
mvn exec:java -Dexec.mainClass=com.scylladb.alternator.test.Demo2 -Dexec.classpathScope=test
```

### Asyncronous operation in SDK v2

When using SDK v2, you can achieve better scalability and performance using the asynchronous
versions of API calls and `java.util.concurrent` completion chaining. 
To create a `DynamoDbAsyncClient` using alternator load balancing, the code would look something like:

```java
import com.scylladb.alternator.AlternatorAsyncHttpClient;
...

DynamoDbAsyncClientBuilder b = DynamoDbAsyncClient.builder().region(regoin);

AlternatorAsyncHttpClient.Builder cb = AlternatorAsyncHttpClient
                        .builder(endpoint_uri);
b.httpClientBuilder(cb);
b.endpointOverride(endpoint_uri);

DynamoDbAsyncClient dynamoDBClient = b.build();

```

You can see `src/test/java/com/scylladb/alternator/test/Demo3.java` for a
complete example of using client-side load balancing with AWS SDK for Java v2 asynchronous API.
After building with `mvn package`, you can run this demo with the command:
```
mvn exec:java -Dexec.mainClass=com.scylladb.alternator.test.Demo3 -Dexec.classpathScope=test
```

## Note about the difference between v1 and v2 support

For historic reasons, our support for v1 and v2, explained above, is
implemented differently. Whereas for v1 we have a simple implementation,
based on the SDK's RequestHandler2 extension point, for v2 we rely on a
significantly more complex implementation, which replaces the HTTP server
implementation by a different one (and our jar contains a modified copy
of Apache's HTTP server).

One of the implications of the different implementation is how SSL requests
work differently in both implementations:

In the RequestHandler2-based v1 implementation, the request is prepared
for one specific Alternator node's IP address as an endpoint, e.g.,
"https://1.2.3.4" when we decide to send the request to node 1.2.3.4.
If SSL is used, this means that this node will need to have an SSL
certificate for its own IP address - and each node needs a different
certificate.
In contrast, in the v2 implementation the request is sent to different nodes,
but is **prepared** as if it's going to one hostname (defined as
`AlternatorHttpClient.FAKE_HOST`). So in this case, all nodes need to have
the _same_ SSL certificate, made out to the this single hostname.

This different implementation only matters for SLL connections, and today
we believe that the approach we used for v1 is more useful, so in the
future we plan to use it for v2 as well. In v2 we should probably use
ExecutionInterceptor, which has a similar goal to v1's RequestHandler2.
