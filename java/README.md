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
library described here supports both (our version 2 support requires 2.20
or above).

## Add `load-balancing` to your project

### Maven Dependency

Add the `load-balancing` dependency to your Maven project by adding the
following `dependency` to your `pom.xml` definition:

~~~ xml
<dependency>
  <groupId>com.scylladb.alternator</groupId>
  <artifactId>load-balancing</artifactId>
  <version>1.0.0</version>
</dependency>
~~~

You can find the latest version [here](https://central.sonatype.com/artifact/com.scylladb.alternator/load-balancing).

### Alternatively, build the LoadBalancing jar
To build a jar of the Alternator client-side load balancer, use
```
mvn package
```
Which creates `target/load-balancing-1.0.0-SNAPSHOT.jar`.

## Usage

As explained above, this package does not _replace_ the AWS SDK for Java, but
accompanies it, and either version 1 or 2 of the AWS SDK for Java can be
used (the details on how to use are slightly different for each version,
so will be explained in separate sections below).

As we show below, the package provides a new mechanism to configure a
DynamoDB (v1) or DynamoDbClient (v2) object, which the application
can then use normally using the standard AWS SDK for Java, to make
requests. The load balancer library ensures that each of these requests
goes to a different live Alternator node.

The load balancer library is also responsible for _discovering_ which
Alternator nodes exist, and maintaining this list as the Alternator cluster
changes. It does this using an additional background thread, which
periodically polls one of the known nodes, asking it for a list of all other
nodes (in this data-center).

### Using the library, in AWS SDK for Java v1

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

#### Option 1: Using `AlternatorDynamoDBClientBuilder` (Recommended)

The simplest way to use the Alternator load balancer is to use the `AlternatorDynamoDBClientBuilder`,
which provides a familiar builder API that mirrors `AmazonDynamoDBClientBuilder`:

```java
    import com.scylladb.alternator.AlternatorDynamoDBClientBuilder;

AmazonDynamoDB client = AlternatorDynamoDBClientBuilder.standard()
    .withEndpoint("https://127.0.0.1:8043")
    .withCredentials("myusername", "mypassword")
    .build();
DynamoDB dynamodb = new DynamoDB(client);
```

The `AlternatorDynamoDBClientBuilder` automatically integrates the `AlternatorRequestHandler`
to provide load balancing across all Alternator nodes. You can also configure datacenter and
rack filtering:

```java
    import com.scylladb.alternator.AlternatorDynamoDBClientBuilder;
import com.scylladb.alternator.AlternatorConfig;

// Using AlternatorConfig
AlternatorConfig config = AlternatorConfig.builder()
    .withDatacenter("dc1")
    .withRack("rack1")
    .build();

    AmazonDynamoDB client = AlternatorDynamoDBClientBuilder.standard()
        .withEndpoint("https://127.0.0.1:8043")
        .withCredentials("myusername", "mypassword")
        .withAlternatorConfig(config)
        .build();

    // Or using individual methods (backward compatible)
    AmazonDynamoDB client = AlternatorDynamoDBClientBuilder.standard()
        .withEndpoint("https://127.0.0.1:8043")
        .withCredentials("myusername", "mypassword")
        .withDatacenter("dc1")
        .withRack("rack1")
        .build();
```

#### Option 2: Using `AlternatorRequestHandler` directly (Outdated)

Alternatively, you can manually add the `AlternatorRequestHandler` to an existing
client builder:

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

The parameter `uri` is one known Alternator node, which is then contacted to
discover the rest. After this initialization, this original node may go down
at any time - any other already-known node can be used to retrieve the node
list, and we no longer rely on the original node.

The region passed to `withRegion` does not matter (and can be any string),
because `AlternatorRequestHandler` will override the chosen endpoint anyway.
Unfortunately we can't just drop the `withRegion()` call, because without
it the library will expect to find a default region in the configuration file
and complain when it is missing.

You can see `src/test/java/com/scylladb/alternator/test/Demo1.java` for a
complete example of using client-side load balancing with AWS SDK for Java v1.
After building with `mvn package`, you can run this demo with the command:
```
mvn exec:java -Dexec.mainClass=com.scylladb.alternator.test.Demo1 -Dexec.classpathScope=test
```

### Using the library, in AWS SDK for Java v2

An application using AWS SDK for Java v2 creates a `DynamoDbClient` object
and then uses it to perform various requests. Traditionally, to create such
an object, an application that wishes to connect to a specific URL would use
code that looks something like this:

```java
    static AwsCredentialsProvider myCredentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create("myuser", "mypassword"));
    URI uri = URI.create("https://127.0.0.1:8043");
    DynamoDbClient client = DynamoDbClient.builder()
        .region(Region.US_EAST_1)
        .endpointOverride(url)
        .credentialsProvider(myCredentials)
        .build();
```

The `region()` chosen doesn't matter when the endpoint is explicitly chosen
with `endpointOverride()`, but nevertheless should be specified otherwise the
SDK will try to look it up in a configuration file, and complain if it isn't
set there.

#### Option 1: Using `AlternatorDynamoDbClient` (Recommended)

The simplest way to use the Alternator load balancer is to use the `AlternatorDynamoDbClient.builder()`,
which provides a familiar builder API that implements `DynamoDbClientBuilder`:

```java
    import com.scylladb.alternator.AlternatorDynamoDbClient;

static AwsCredentialsProvider myCredentials =
    StaticCredentialsProvider.create(AwsBasicCredentials.create("myuser", "mypassword"));

DynamoDbClient client = AlternatorDynamoDbClient.builder()
    .endpointOverride(URI.create("https://127.0.0.1:8043"))
    .credentialsProvider(myCredentials)
    .build();
```

The `AlternatorDynamoDbClient` automatically integrates the `AlternatorEndpointProvider`
to provide load balancing across all Alternator nodes. You can also configure datacenter and
rack filtering:

```java
    import com.scylladb.alternator.AlternatorConfig;
import com.scylladb.alternator.AlternatorDynamoDbClient;

AlternatorConfig config = AlternatorConfig.builder()
    .withDatacenter("dc1")
    .withRack("rack1")
    .build();

DynamoDbClient client = AlternatorDynamoDbClient.builder()
    .endpointOverride(URI.create("https://127.0.0.1:8043"))
    .credentialsProvider(myCredentials)
    .withAlternatorConfig(config)
    .build();
```

#### Option 2: Using `AlternatorEndpointProvider` directly (Outdated)

Alternatively, you can manually use the `AlternatorEndpointProvider`:

```java
    import com.scylladb.alternator.AlternatorEndpointProvider;

    static AwsCredentialsProvider myCredentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create("myuser", "mypassword"));
    URI uri = URI.create("https://127.0.0.1:8043");
    AlternatorEndpointProvider alternatorEndpointProvider = new AlternatorEndpointProvider(uri);
    DynamoDbClient client = DynamoDbClient.builder()
        .region(Region.US_EAST_1)
        .endpointProvider(alternatorEndpointProvider)
        .credentialsProvider(myCredentials)
        .build();
```

Please note that the `endpointProvider()` API is new to AWS Java SDK 2.20
(Release February 2023), so you should use this version or newer.

The application can then use this `DynamoDbClient` object completely normally,
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

#### Asynchronous operation in SDK v2

When using SDK v2, you can achieve better scalability and performance using the asynchronous
versions of API calls and `java.util.concurrent` completion chaining.

##### Option 1: Using `AlternatorDynamoDbAsyncClient` (Recommended)

The simplest way to create a `DynamoDbAsyncClient` with Alternator load balancing is to use
the `AlternatorDynamoDbAsyncClient.builder()`:

```java
    import com.scylladb.alternator.AlternatorDynamoDbAsyncClient;

static AwsCredentialsProvider myCredentials =
    StaticCredentialsProvider.create(AwsBasicCredentials.create("myuser", "mypassword"));

DynamoDbAsyncClient client = AlternatorDynamoDbAsyncClient.builder()
    .endpointOverride(URI.create("https://127.0.0.1:8043"))
    .credentialsProvider(myCredentials)
    .build();
```

You can also configure datacenter and rack filtering:

```java
    import com.scylladb.alternator.AlternatorConfig;
import com.scylladb.alternator.AlternatorDynamoDbAsyncClient;

AlternatorConfig config = AlternatorConfig.builder()
    .withDatacenter("dc1")
    .withRack("rack1")
    .build();

DynamoDbAsyncClient client = AlternatorDynamoDbAsyncClient.builder()
    .endpointOverride(URI.create("https://127.0.0.1:8043"))
    .credentialsProvider(myCredentials)
    .withAlternatorConfig(config)
    .build();
```

##### Option 2: Using `AlternatorEndpointProvider` directly (Outdated)

Alternatively, you can manually use the `endpointProvider()` method on the
`DynamoDbAsyncClientBuilder`, passing an `AlternatorEndpointProvider` object:

```java
    import com.scylladb.alternator.AlternatorEndpointProvider;

    static AwsCredentialsProvider myCredentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create("myuser", "mypassword"));
    URI uri = URI.create("https://127.0.0.1:8043");
    AlternatorEndpointProvider alternatorEndpointProvider = new AlternatorEndpointProvider(uri);
    DynamoDbAsyncClient client = DynamoDbAsyncClient.builder()
        .region(Region.US_EAST_1)
        .endpointProvider(alternatorEndpointProvider)
        .credentialsProvider(myCredentials)
        .build();
```

You can see `src/test/java/com/scylladb/alternator/test/Demo3.java` for a
complete example of using client-side load balancing with AWS SDK for Java v2 asynchronous API.
After building with `mvn package`, you can run this demo with the command:
```
mvn exec:java -Dexec.mainClass=com.scylladb.alternator.test.Demo3 -Dexec.classpathScope=test
```
