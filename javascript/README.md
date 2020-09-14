# Alternator Load Balancing: javascript

This directory contains a load-balancing wrapper for the javascript driver.

## Contents

Alternator load balancing wrapper consists of a single `Alternator.js` module. By using this module's initialization routines it's possible to set the driver up for client-side load balancing.

## Usage

In order to switch from the stock driver to Alternator load balancing, it's enough to replace the initialization code in the existing application:
```javascript
 AWS.config.update({
   region: "us-west-2"
 });
```
to
```javascript
alternator.init(AWS, "http", 8000, ["127.0.0.1"]);
```
After that all following requests will be implicitly routed to Alternator nodes.
Parameters accepted by the Alternator client are:
1. `protocol`: `"http"` or `"https"`, used for client-server communication.
2. `port`: Port of the Alternator nodes.
The code assumes that all Alternator nodes share an identical port for client communication.
3. `hosts`: list of hostnames of the Alternator nodes.
Periodically, one of the nodes will be contacted to retrieve the cluster topology information.

It's enough to provide a single Alternator node to the initialization function, as the list of active nodes is periodically refreshed in the background.

## Details

Alternator load balancing for javascript works by overriding internal methods of the stock javascript driver, which causes the requests to different Alternator nodes. Initially, the driver contacts one of the Alternator nodes and retrieves the list of active nodes which can be use to accept user requests. This list is perodically refreshed in order to ensure that any topology changes are taken into account. Once a client sends a request, the load balancing layer picks one of the active Alternator nodes as the target. Currently, nodes are picked in a round-robin fashion.

