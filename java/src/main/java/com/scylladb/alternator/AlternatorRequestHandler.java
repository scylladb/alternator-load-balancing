package com.scylladb.alternator;

import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.Request;

import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/* AlternatorRequestHandler is RequestHandler2 implementation for AWS SDK
 * for Java v1. It tells the SDK to replace the endpoint in the request,
 * whatever it was, with the next Alternator node.
 */
public class AlternatorRequestHandler extends RequestHandler2 {

    private static Logger logger = Logger.getLogger(AlternatorRequestHandler.class.getName());

    AlternatorLiveNodes liveNodes;

    public AlternatorRequestHandler(URI seedURI) {
        this(seedURI, "", "");
    }

    public AlternatorRequestHandler(URI seedURI, String datacenter, String rack) {
        liveNodes = new AlternatorLiveNodes(seedURI, datacenter, rack);
        try {
            liveNodes.validate();
            liveNodes.checkIfRackAndDatacenterSetCorrectly();
            if (!datacenter.isEmpty() || !rack.isEmpty()) {
                if (!liveNodes.checkIfRackDatacenterFeatureIsSupported()) {
                    logger.log(Level.SEVERE, String.format("server %s does not support rack or datacenter filtering", seedURI));
                }
            }
        } catch (AlternatorLiveNodes.ValidationError | AlternatorLiveNodes.FailedToCheck e) {
            throw new RuntimeException(e);
        }
        liveNodes.start();
    }

    @Override
    public void beforeRequest(Request<?> request) {
        request.setEndpoint(liveNodes.nextAsURI());
    }
}
