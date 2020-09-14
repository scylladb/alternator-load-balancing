# Alternator Load Balancing: Java

This directory contains a load-balancing wrapper for the Java driver.

## Contents

Alternator load balancing wrapper consists of two files bundled in `com.scylladb.alternator` package: `Alternator.java` and `AlternatorApacheClient.java`. With these imported, it's possible to use `AlternatorClient` class as a drop-in replacement for stock `DynamoDbClient`, which will provide a client-side load balancing layer for the Alternator cluster.

## Usage

In order to switch from the stock driver to Alternator load balancing, it's enough to replace the initialization code in the existing application:
```java
        DynamoDbClient ddb = DynamoDbClient.builder()
                .region(region)
                .build();

```
to
```java
        DynamoDbClient ddb = AlternatorClient.builder("http", "localhost", 8000)
                .region(region)
                .build();
```
After that single change, all requests sent via the `ddb` instance of `DynamoDbClient` will be implicitly routed to Alternator nodes.
Parameters accepted by the Alternator client are:
1. `protocol`: `http` or `https`, used for client-server communication
2. `host`: hostname of one of the Alternator nodes, which should be contacted to retrieve cluster topology information
3. `port`: port of one of the Alternator nodes, which should be contacted to retrieve cluster topology information

## Details

Alternator load balancing for Java works by providing a thin layer which distributes the requests to different Alternator nodes. Initially, the driver contacts one of the Alternator nodes and retrieves the list of active nodes which can be use to accept user requests. This list is perodically refreshed in order to ensure that any topology changes are taken into account. Once a client sends a request, the load balancing layer picks one of the active Alternator nodes as the target. Currently, nodes are picked in a round-robin fashion.

