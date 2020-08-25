# Use 'import boto3_alternator' to make any unmodified boto3 client use
# multiple nodes of an Alternator cluster instead of just one (which you'll
# normally get if providing just one node's IP address as the endpoint URL).
#
# This is important for two reasons: High Availability (the failure of a
# single Alternator node should not prevent the client from proceeding) and
# Load Balancing over all Alternator nodes.
#
# This library provides two alternative implementations:
#   1. Every time that boto3 needs to open a *connection* to DynamoDB,
#      it will pick a different live Alternator node. When a connection
#      breaks, the destination of the new connection will be chosen again.
#   2. Every time that boto3 sends a *request* to DynamoDB, it will send it
#      to a different live Alternator node.
#
# The first implementation is best when a client has numerous threads or boto3
# "resources" - as each of those opens a different connection. But it becomes
# a problem when a client only has one or very few threads or boto3
# "resources". There, the second implementation shines because each request
# will go to a different Alternator node, instead of sending all these
# requests over an already established connection. This means that the second
# implementation is bad when the client has many threads of "resources",
# because each of these resources, instead of keeping one open connection,
# will keep many (the size of botocore's "connection pool") to many different
# Alternator nodes.
#
# Both approaches start an additional thread which periodically updates the
# list of live Alternator nodes by contacting one of the known nodes and
# asking it for a list of the rest (in this data-center).
#
# Both approaches work by "monkey-patching", or instrumenting, different
# library functions:
# 1. The first approach monkey-patches socket.getaddrinfo, so every time
#    boto3 uses it to open a new connection, it will pick a different node.
# 2. The second approach monkey-patch botocore's request-sending function,
#    botocore.httpsession.URLLib3Session.send, so that each request is sent
#    to a different node.
#
#
# To use this library, import it and then run the setup1() or setup2()
# function, like this: (pick setup1 or setup2 for the two different
# implementations):
#
#   import boto3_alternator
#
#   alternator_url = boto3_alternator_3.setup1(
#      # A list of known Alternator nodes. One of them must be responsive.
#      ['127.0.0.1'],
#      # Alternator scheme (http or https) and port
#     'http', 8000,
#      # A "fake" domain name which, if used by the application, will be
#      # resolved to one of the Scylla nodes.
#     'dog.scylla.com')
#
# The setup function returns the "endpoint URL", in this example it will be
# "http://dog.scylla.com:8000/". You will need to use this endpoint URL to
# create the boto3 resource, e.g.,
#
#    dynamodb = boto3.resource('dynamodb', endpoint_url=alternator_url,
#            aws_access_key_id='???', aws_secret_access_key='???')
#
# The "fake domain", in this example, dog.scylla.com, can be any domain name,
# it doesn't need to exist - and in fact, it probably shouldn't. The setup()
# code captures any attempts to use this domain name and forwards them to one
# of the live Alternator nodes, so the real DNS is never asked about
# "dog.scylla.com".

import random
import time
import urllib
import _thread
import botocore

# Periodically update 'livenodes', the list of known live nodes, all of them
# supposedly answering 'alternator_scheme' (http or https) requests on port
# 'alternator_port'. One of these livenodes will be used at random for every
# connection.
# The livenodes list starts with one or more known nodes, but then the
# livenodes_update() thread periodically replaces this list by an up-to-date
# list retrieved from making a "localnodes" requests to one of these nodes.
def livenodes_update():
    global livenodes
    global alternator_port
    global alternator_scheme
    while True:
        # Contact one of the already known nodes by random, to fetch a new
        # list of known nodes.
        ip = random.choice(livenodes)
        url = '{}://{}:{}/localnodes'.format(alternator_scheme, ip, alternator_port)
        try:
            nodes = urllib.request.urlopen(url, None, 1.0).read().decode('ascii')
            a = [x.strip('"').rstrip('"') for x in nodes.strip('[').rstrip(']').split(',')]
            # If we're successful, replace livenodes by the new list
            livenodes = a
        except:
            # TODO: contacting this ip was unsuccessful, remove it from the
            # list of live nodes.
            pass
        # TODO: in extreme situations, we can reach a state where livenodes
        # only lists a few nodes, but all of them are down. For this situation
        # it might be useful to also keep a list of previously-live nodes, and
        # try the livenodes request on them too, not only the known live nodes.
        time.sleep(1)

def setup1(known_nodes, scheme, port, fake_domain):
    global alternator_port
    global alternator_scheme
    global livenodes
    global alternator_fake_domain
    alternator_port = port
    alternator_scheme = scheme
    livenodes = known_nodes
    alternator_fake_domain = fake_domain
    # FIXME: check if ran setup twice and don't start the new thread or instrument
    _thread.start_new_thread(livenodes_update,())
    # Instrument the socket.getaddrinfo function
    import socket
    orig_getaddrinfo = socket.getaddrinfo
    def new_getaddrinfo(host, *args):
        global alternator_fake_domain
        global livenodes
        if (host == alternator_fake_domain):
            # TODO: consider whether random, or round-robin, is better.
            host = random.choice(livenodes)
            print("picked {}".format(host))
        return orig_getaddrinfo(host, *args)
    socket.getaddrinfo = new_getaddrinfo
    return "{}://{}:{}".format(alternator_scheme, fake_domain, alternator_port)

def setup2(known_nodes, scheme, port, fake_domain):
    global alternator_port
    global alternator_scheme
    global livenodes
    global alternator_fake_domain
    global alternator_url
    alternator_port = port
    alternator_scheme = scheme
    livenodes = known_nodes
    alternator_fake_domain = fake_domain
    alternator_url = "{}://{}:{}/".format(alternator_scheme, fake_domain, alternator_port)
    # FIXME: check if ran setup twice and don't start the new thread or instrument
    _thread.start_new_thread(livenodes_update,())
    # Instrument botocore.httpsession.URLLib3Session.send, to pick a different
    # Alternator node for each request. Connections are still reused -
    # botocore will keep a pool of these connections, and will reuse an
    # existing connection when it wants to send another request to a node to
    # which it had previously sent to.
    # TODO: consider if we need to increase the PoolManager size
    # (botocore.httpsession.MAX_POOL_CONNECTIONS) to be at least the number of nodes,
    # otherwise connections will not be reused!
    orig_send = botocore.httpsession.URLLib3Session.send
    def new_send(self, request):
        global alternator_url
        global alternator_fake_domain
        global livenodes
        if (request.url == alternator_url):
            # TODO: consider whether random, or round-robin, is better.
            host = random.choice(livenodes)
            request.url = "{}://{}:{}/".format(alternator_scheme, host, alternator_port)
            print("picked {}".format(request.url))
            # botocore does not set a "Host" header, but it turns out the
            # lower-level urllib3 library adds one automatically using the
            # connection's url, which we now changed to be an IP address.
            # This means that botocore calculated the requests signature
            # using the "Host" known to it (the fake_domain) but Alternator
            # will receive a different one (the IP address) and will report
            # signature mismatch. So we must set the Host header properly.
            request.headers['Host'] = '{}:{}'.format(alternator_fake_domain, alternator_port)
        return orig_send(self, request)
    botocore.httpsession.URLLib3Session.send = new_send
    return alternator_url

