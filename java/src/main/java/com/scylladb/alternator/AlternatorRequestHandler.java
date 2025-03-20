package com.scylladb.alternator;

import com.amazonaws.Request;
import com.amazonaws.handlers.RequestHandler2;
import java.net.URI;
import java.util.logging.Logger;

/* AlternatorRequestHandler is RequestHandler2 implementation for AWS SDK
 * for Java v1. It tells the SDK to replace the endpoint in the request,
 * whatever it was, with the next Alternator node.
 */
/**
 * AlternatorRequestHandler class.
 *
 * @author dmitry.kropachev
 */
public class AlternatorRequestHandler extends RequestHandler2 {

  private static Logger logger = Logger.getLogger(AlternatorRequestHandler.class.getName());

  AlternatorLiveNodes liveNodes;

  /**
   * Constructor for AlternatorRequestHandler.
   *
   * @param seedURI a {@link java.net.URI} object
   */
  public AlternatorRequestHandler(URI seedURI) {
    this(seedURI, "", "");
  }

  /**
   * Constructor for AlternatorRequestHandler.
   *
   * @param seedURI a {@link java.net.URI} object
   * @param datacenter a {@link java.lang.String} object
   * @param rack a {@link java.lang.String} object
   * @since 1.0.1
   */
  public AlternatorRequestHandler(URI seedURI, String datacenter, String rack) {
    liveNodes = AlternatorLiveNodes.pickSupportedDatacenterRack(seedURI, datacenter, rack);
    liveNodes.start();
  }

  /** {@inheritDoc} */
  @Override
  public void beforeRequest(Request<?> request) {
    request.setEndpoint(liveNodes.nextAsURI());
  }
}
