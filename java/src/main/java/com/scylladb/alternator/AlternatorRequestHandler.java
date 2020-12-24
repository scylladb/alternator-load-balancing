package com.scylladb.alternator;

import com.scylladb.alternator.AlternatorLiveNodes;

import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.Request;

import java.net.URI;

/* AlternatorRequestHandler is RequestHandler2 implementation for AWS SDK
 * for Java v1. It tells the SDK to replace the endpoint in the request,
 * whatever it was, with the next Alternator node.
 */
public class AlternatorRequestHandler extends RequestHandler2 {
    AlternatorLiveNodes liveNodes;
    public AlternatorRequestHandler(URI seedURI) {
        liveNodes = AlternatorLiveNodes.create(seedURI);
    }
    @Override
    public void beforeRequest(Request<?> request) {
        request.setEndpoint(liveNodes.nextAsURI());
    }
}
