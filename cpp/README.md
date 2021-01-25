# Alternator Load Balancing: C++

This directory contains a load-balancing wrapper for the C++ driver.

## Contents

Alternator load balancing wrapper consists of a single header: `AlternatorClient.h`. With this header included, it's possible to use `AlternatorClient` class as a drop-in replacement for stock `DynamoDBClient`, which will provide a client-side load balancing layer for the Alternator cluster.

## Usage

In order to switch from the stock driver to Alternator load balancing, it's enough to replace the initialization code in the existing application:
```cpp
        Aws::DynamoDB::DynamoDBClient dynamoClient(clientConfig);
```
to
```cpp
        AlternatorClient dynamoClient("http", "localhost", "8000", clientConfig);
        dynamoClient.StartNodeUpdater(std::chrono::seconds(1));
```
After that single change, all requests sent via the `dynamoClient` instance of `DynamoDBClient` will be implicitly routed to Alternator nodes.
Parameters accepted by the Alternator client are:
1. `protocol`: `http` or `https`, used for client-server communication
2. `addr`: hostname of one of the Alternator nodes, which should be contacted to retrieve cluster topology information
3. `port`: port of the Alternator nodes - each node is expected to use the same port number

Running an update thread (`dynamoClient.StartNodeUpdater(std::chrono::seconds(1))`) is optional, but is highly recommended due to possible topology changes in a live cluster - the active node list can change in time. The update thread accepts a single argument, which describes how often the node list is updated.

## Details

Alternator load balancing for C++ works by providing a thin layer which distributes the requests to different Alternator nodes. Initially, the driver contacts one of the Alternator nodes and retrieves the list of active nodes which can be use to accept user requests. This list can be perodically refreshed in order to ensure that any topology changes are taken into account. Once a client sends a request, the load balancing layer picks one of the active Alternator nodes as the target. Currently, nodes are picked in a round-robin fashion.

## Example

An example program can be found in the `examples` directory. The program tries to connect to an alternator cluster and then:
 * creates a table
 * fills the table with 5 example items
 * scans the table to verify that the items were properly inserted

The demo can be compiled via CMake:
```bash
cd examples
mkdir build
cmake ..
```

By default, the demo program tries to connect to localhost, via http, on port 8000. Please edit the source code to provide your own endpoint if necessary.

In order to run the demo, just pass the table name as the first argument:
```bash
./demo test_table1
```
