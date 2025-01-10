package com.scylladb.alternator;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.awssdk.endpoints.Endpoint;
import software.amazon.awssdk.services.dynamodb.endpoints.DynamoDbEndpointParams;
import software.amazon.awssdk.services.dynamodb.endpoints.DynamoDbEndpointProvider;

// AWS Java SDK v2 allows providing a DynamoDbEndpointProvider which can
// choose a different endpoint for each request. Here we implement an
// AlternatorEndpointProvider, which maintains up-to-date knowledge of the
// live nodes in Alternator data center (by holding a AlternatorLiveNodes
// object), and choose a different node for each request.
/**
 * AlternatorEndpointProvider class.
 *
 * @author dmitry.kropachev
 */
public class AlternatorEndpointProvider implements DynamoDbEndpointProvider {
  private final AlternatorLiveNodes liveNodes;
  private final Map<URI, CompletableFuture<Endpoint>> futureCache;
  private static Logger logger = Logger.getLogger(AlternatorEndpointProvider.class.getName());

  /**
   * Constructor for AlternatorEndpointProvider.
   *
   * @param seedURI a {@link java.net.URI} object
   */
  public AlternatorEndpointProvider(URI seedURI) {
    this(seedURI, "", "");
  }

  /**
   * Constructor for AlternatorEndpointProvider.
   *
   * @param seedURI a {@link java.net.URI} object
   * @param datacenter a {@link java.lang.String} object
   * @param rack a {@link java.lang.String} object
   * @since 1.0.1
   */
  public AlternatorEndpointProvider(URI seedURI, String datacenter, String rack) {
    futureCache = new ConcurrentHashMap<>();
    liveNodes = new AlternatorLiveNodes(seedURI, datacenter, rack);
    try {
      liveNodes.validate();
      liveNodes.checkIfRackAndDatacenterSetCorrectly();
      if (!datacenter.isEmpty() || !rack.isEmpty()) {
        if (!liveNodes.checkIfRackDatacenterFeatureIsSupported()) {
          logger.log(
              Level.SEVERE,
              String.format("server %s does not support rack or datacenter filtering", seedURI));
        }
      }
    } catch (AlternatorLiveNodes.ValidationError | AlternatorLiveNodes.FailedToCheck e) {
      throw new RuntimeException(e);
    }
    liveNodes.start();
  }

  /** {@inheritDoc} */
  @Override
  public CompletableFuture<Endpoint> resolveEndpoint(DynamoDbEndpointParams endpointParams) {
    URI uri = liveNodes.nextAsURI();
    CompletableFuture<Endpoint> endpoint = futureCache.getOrDefault(uri, null);
    if (endpoint != null) {
      return endpoint;
    }
    endpoint = new CompletableFuture<>();
    endpoint.complete(Endpoint.builder().url(uri).build());
    futureCache.put(uri, endpoint);
    return endpoint;
  }
}
