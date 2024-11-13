package com.scylladb.alternator;

import com.scylladb.alternator.AlternatorLiveNodes;

import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.Request;

import java.io.IOException;
import java.net.URI;

/* AlternatorRequestHandler is RequestHandler2 implementation for AWS SDK
 * for Java v1. It tells the SDK to replace the endpoint in the request,
 * whatever it was, with the next Alternator node.
 */
public class AlternatorRequestHandler extends RequestHandler2 {
    private final AlternatorLiveNodes liveNodes;

    public AlternatorRequestHandler(URI seedURI) {
        liveNodes = AlternatorLiveNodes.create(seedURI);
    }
    @Override
    public void beforeRequest(Request<?> request) {
        request.setEndpoint(liveNodes.nextAsURI());
    }

    private void checkNotStarted() {
        if (liveNodes.getState() != Thread.State.NEW) {
            throw new IllegalStateException("AlternatorRequestHandler is already started, you can't change");
        }
    }

    public AlternatorRequestHandler setRack(String rack) {
        this.checkNotStarted();
        liveNodes.setRack(rack);
        return this;
    }

    public AlternatorRequestHandler checkIfRackAndDatacenterSetCorrectly() throws RuntimeException {
        this.checkNotStarted();
        RuntimeException e = liveNodes.checkIfRackAndDatacenterSetCorrectly();
        if (e != null) {
            throw e;
        }
        return this;
    }

    public AlternatorRequestHandler setDatacenter(String datacenter) {
        this.checkNotStarted();
        liveNodes.setDatacenter(datacenter);
        return this;
    }

    public AlternatorRequestHandler start() {
        liveNodes.start();
        return this;
    }
}
