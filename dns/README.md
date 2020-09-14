# Alternator - DNS load balancing - proof-of-concept

## Introduction
As explained in the [toplevel README](../README.md), DynamoDB clients
are usually aware of a **single endpoint**, a single URL to which they
connect - e.g., `http://dynamodb.us-east-1.amazonaws.com`. One of the
approaches with which requests to a single URL can be directed many
Scylla nodes is via a DNS server: We can configure a DNS server to return
one of the live Scylla nodes (in the relevant data center). If we assume
that there are many different clients, the overall load will be balanced.

Scylla provides a simple HTTP request, "`/localnodes`", with which we
can retrieve the current list of live nodes in this data center.

## dns-loadbalancer.py
**Any** configurable DNS server can be used for this purpose. A script
can periodically fetch the current list of live nodes by sending a
`/localnodes` request to any previously-known live node. The script can
then configure the DNS server to randomly return nodes from this list (or
return random subsets from this list) for the chosen domain name.

The Python program `dns-loadbalancer.py` is a self-contained example of
this approach. It is not meant to be used for production workloads, but
more as a *proof of concept*, of what can be done. The code uses the Python
library **dnslib** to serve DNS requests on port 53 (both UDP and TCP).
Requests to _any_ domain name are answered by one of the live Scylla nodes
at random. The Python code also periodically (once every second) makes a
`/localnodes` request to one of the known nodes to refresh the list of
live nodes.

To use `dns-loadbalancer.py`, run it on some machine, and set up an
existing name server to point a specific domain name, e.g.,
`alternator.example.com` to this name server (i.e., an `NS` record).
Now, whenever the application tries to resolve `alternator.example.com`
our machine running `dns-loadbalancer.py` gets the request, and can
respond with a random Scylla node.

If your setup does not have any DNS server or domain name which you can
control (as `alternator.example.com` in the above example), an alternative
setup is to configure the client machine to use the demo DNS as its
default DNS server. This means that the demo DNS server will receive **all**
DNS requests, so the code needs to be changed (a bit) to only return random
Scylla nodes for a specific "fake" domain (e.g., `alternator.example.com`,
even if you don't control that domain), and pass on every other requests to
a real name server.

`dns-loadbalancer.py` should be edited to change the Alternator port
number (which must be identical across the cluster) - in the `alternator_port`
variable - and also an initial list of known Scylla nodes in the `livenodes`
variable. As explained above, this list will be refreshed every one second,
but this process must start by at least one known nodes, which we can
contact to find the list of all the nodes.

## Example

In the following example, Scylla is running on port 8000 on three
IP addresses - 127.0.0.1, 127.0.0.2, and 127.0.0.3. The initial
`livenodes` list contains just 127.0.0.1.

```
$ sudo ./dns-loadbalancer.py
updating livenodes from http://127.0.0.1:8000/localnodes
['127.0.0.2', '127.0.0.3', '127.0.0.1']
...
```

```
$ dig @localhost alternator.example.com
...
;; ANSWER SECTION:
alternator.example.com.	4	IN	A	127.0.0.3
$ dig @localhost alternator.example.com
...
;; ANSWER SECTION:
alternator.example.com.	4	IN	A	127.0.0.2
```

Note how each response returns one of the three live nodes at random,
with a TTL of 4 seconds.
