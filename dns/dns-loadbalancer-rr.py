#!/usr/bin/python3

# This proof-of-concept is a variant of dns-loadbalancer.py. Whereas the
# latter returns one random Scylla node in response to every request, the
# implementation in this file returns the *entire* list of live nodes, shifted
# cyclically by a random amount. This technique allows clients which can make
# use of the entire list to use it - while clients who can't and use the entire
# list and use the first one will still get a random node, as happens in
# dns-loadbalancer.py. This technique is known as "Round-robin DNS" - see
#    https://en.wikipedia.org/wiki/Round-robin_DNS

import dnslib.server
import dnslib
import random
import _thread
import urllib.request
import time

# The list of live nodes, all of them supposedly answering HTTP requests on
# alternator_port. All of these nodes will be returned, shifted by a random
# amount, from every DNS request.
# This list starts with one or more known nodes, but then the
# livenodes_update() thread periodically replaces this list by an up-to-date
# list retrieved from makeing a "/localnodes" requests to one of these nodes.
livenodes = ['127.0.0.1']
alternator_port = 8000
def livenodes_update():
    global alternator_port
    global livenodes
    while True:
        # Contact one of the already known nodes by random, to fetch a new
        # list of known nodes.
        # TODO: We could reuse the HTTP connection (and prefer to make the
        # request to the same node again, and not a random node).
        ip = random.choice(livenodes)
        url = 'http://{}:{}/localnodes'.format(ip, alternator_port)
        print('updating livenodes from {}'.format(url))
        try:
            nodes = urllib.request.urlopen(url, None, 1.0).read().decode('ascii')
            a = [x.strip('"').rstrip('"') for x in nodes.strip('[').rstrip(']').split(',')]
            # If we're successful, replace livenodes by the new list
            livenodes = a
            print(livenodes)
        except:
            # Contacting this ip was unsuccessful, we could remove remove it
            # from the list of live nodes, but tais is not a good idea if
            # all nodes are temporarily down. In any case, when we do reach
            # a live node, we'll replace the entire list.
            pass
        time.sleep(1)
_thread.start_new_thread(livenodes_update,())

def random_shift(l):
    shift = random.randrange(len(l))
    return l[shift::] + l[:shift:]

class Resolver:
    def resolve(self, request, handler):
        qname = request.q.qname
        reply = request.reply()
        # Note responses have TTL 5, as in Amazon's Dynamo DNS
        for ip in random_shift(livenodes):
            reply.add_answer(*dnslib.RR.fromZone('{} 5 A {}'.format(qname, ip)))
        return reply

resolver = Resolver()
logger = dnslib.server.DNSLogger(prefix=True)
tcp_server = dnslib.server.DNSServer(Resolver(), port=53, address='localhost', logger=logger, tcp=True)
tcp_server.start_thread()
udp_server = dnslib.server.DNSServer(Resolver(), port=53, address='localhost', logger=logger, tcp=False)
udp_server.start_thread()

try:
    while True:
        time.sleep(10)
except KeyboardInterrupt:
    print('Goodbye!')
finally:
    tcp_server.stop()
    udp_server.stop()
