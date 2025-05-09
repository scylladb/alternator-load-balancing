package com.scylladb.alternator;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

/**
 * Maintains and automatically updates a list of known live Alternator nodes. Live Alternator nodes
 * should answer alternatorScheme (http or https) requests on port alternatorPort. One of these
 * livenodes will be used, at round-robin order, for every connection. The list of live nodes starts
 * with one or more known nodes, but then a thread periodically replaces this list by an up-to-date
 * list retrieved from making a "/localnodes" requests to one of these nodes.
 *
 * @author dmitry.kropachev
 */
public class AlternatorLiveNodes extends Thread {
  private final String alternatorScheme;
  private final int alternatorPort;
  private final AtomicReference<List<URI>> liveNodes;
  private final List<URI> initialNodes;
  private final AtomicInteger nextLiveNodeIndex;
  private final String rack;
  private final String datacenter;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final HttpClient httpClient;

  private static Logger logger = Logger.getLogger(AlternatorLiveNodes.class.getName());

  /** {@inheritDoc} */
  @Override
  public void run() {
    logger.log(Level.INFO, "AlternatorLiveNodes thread started");
    try {
      for (; ; ) {
        try {
          updateLiveNodes();
        } catch (IOException e) {
          logger.log(Level.SEVERE, "AlternatorLiveNodes failed to sync nodes list", e);
        }
        try {
          Thread.sleep(5000);
        } catch (InterruptedException e) {
          logger.log(Level.INFO, "AlternatorLiveNodes thread interrupted and stopping");
          return;
        }
      }
    } finally {
      logger.log(Level.INFO, "AlternatorLiveNodes thread stopped");
    }
  }

  /**
   * Constructor for AlternatorLiveNodes.
   *
   * @param liveNode a {@link java.net.URI} object
   * @param datacenter a {@link java.lang.String} object
   * @param rack a {@link java.lang.String} object
   */
  public AlternatorLiveNodes(URI liveNode, String datacenter, String rack) {
    this(
        Collections.singletonList(liveNode),
        liveNode.getScheme(),
        liveNode.getPort(),
        datacenter,
        rack);
  }

  /**
   * Constructor for AlternatorLiveNodes.
   *
   * @param liveNodes a {@link java.util.List} object
   * @param scheme a {@link java.lang.String} object
   * @param port a int
   * @param datacenter a {@link java.lang.String} object
   * @param rack a {@link java.lang.String} object
   * @since 1.0.1
   */
  public AlternatorLiveNodes(
      List<URI> liveNodes, String scheme, int port, String datacenter, String rack) {
    if (liveNodes == null || liveNodes.isEmpty()) {
      throw new RuntimeException("liveNodes cannot be null or empty");
    }
    this.alternatorScheme = scheme;
    this.initialNodes = liveNodes;
    this.liveNodes = new AtomicReference<>();
    this.alternatorPort = port;
    this.nextLiveNodeIndex = new AtomicInteger(0);
    this.rack = rack;
    this.datacenter = datacenter;
    try {
      this.validate();
    } catch (ValidationError e) {
      throw new RuntimeException(e);
    }
    this.liveNodes.set(initialNodes);
    this.httpClient = prepareHttpClient();
  }

  /** {@inheritDoc} */
  @Override
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    // setDaemon(true) allows the program to exit even if the thread is still running.
    this.setDaemon(true);
    super.start();
  }

  /**
   * validateURI.
   *
   * @param uri a {@link java.net.URI} object
   * @throws com.scylladb.alternator.AlternatorLiveNodes.ValidationError if any.
   * @since 1.0.1
   */
  public void validateURI(URI uri) throws ValidationError {
    try {
      uri.toURL();
    } catch (MalformedURLException e) {
      throw new ValidationError("Invalid URI: " + uri, e);
    }
  }

  /**
   * validate.
   *
   * @throws com.scylladb.alternator.AlternatorLiveNodes.ValidationError if any.
   * @since 1.0.1
   */
  public void validate() throws ValidationError {
    this.validateConfig();
    for (URI liveNode : initialNodes) {
      this.validateURI(liveNode);
    }
  }

  public static class ValidationError extends Exception {
    public ValidationError(String message) {
      super(message);
    }

    public ValidationError(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private void validateConfig() throws ValidationError {
    try {
      // Make sure that `alternatorScheme` and `alternatorPort` are correct values
      this.hostToURI("1.1.1.1");
    } catch (MalformedURLException | URISyntaxException e) {
      throw new ValidationError("failed to validate configuration", e);
    }
  }

  private URI hostToURI(String host) throws URISyntaxException, MalformedURLException {
    URI uri = new URI(alternatorScheme, null, host, alternatorPort, null, null, null);
    // Make sure that URI to URL conversion works
    uri.toURL();
    return uri;
  }

  /**
   * nextAsURI.
   *
   * @return a {@link java.net.URI} object
   */
  public URI nextAsURI() {
    List<URI> nodes = liveNodes.get();
    if (nodes.isEmpty()) {
      throw new IllegalStateException("No live nodes available");
    }
    return nodes.get(Math.abs(nextLiveNodeIndex.getAndIncrement() % nodes.size()));
  }

  /**
   * nextAsURI.
   *
   * @param path a {@link java.lang.String} object
   * @param query a {@link java.lang.String} object
   * @return a {@link java.net.URI} object
   * @since 1.0.1
   */
  public URI nextAsURI(String path, String query) {
    try {
      URI uri = this.nextAsURI();
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), path, query, null);
    } catch (URISyntaxException e) {
      // Should never happen, nextAsURI content is already validated
      throw new RuntimeException(e);
    }
  }

  // Utility function for reading the entire contents of an input stream
  // (which we assume will be fairly short)
  private static String streamToString(HttpEntity body) throws IOException {
    if (body == null) {
      return "";
    }
    InputStream stream = body.getContent();
    if (stream == null) {
      return "";
    }
    Scanner s = new Scanner(stream).useDelimiter("\\A");
    String result = s.hasNext() ? s.next() : "";
    stream.close();
    return result;
  }

  private void updateLiveNodes() throws IOException {
    List<URI> newHosts = getNodes(nextAsLocalNodesURI());
    if (!newHosts.isEmpty()) {
      liveNodes.set(newHosts);
      logger.log(Level.FINE, "Updated hosts to " + liveNodes);
    }
  }

  private List<URI> getNodes(URI uri) throws IOException {
    // Note that despite this being called HttpURLConnection, it actually
    // supports HTTPS as well.
    HttpResponse httpResponse;
    try {
      httpResponse = httpClient.execute(new HttpGet(uri));
      if (httpResponse.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
        return Collections.emptyList();
      }
    } catch (ProtocolException e) {
      // It can happen only of conn is already connected or "GET" is not a valid method
      // Both cases not true, os it should happen
      throw new RuntimeException(e);
    }
    String response = streamToString(httpResponse.getEntity());
    // response looks like: ["127.0.0.2","127.0.0.3","127.0.0.1"]
    response = response.trim();
    response = response.substring(1, response.length() - 1);
    String[] list = response.split(",");
    List<URI> newHosts = new ArrayList<>();
    for (String host : list) {
      if (host.isEmpty()) {
        continue;
      }
      host = host.trim();
      host = host.substring(1, host.length() - 1);
      try {
        newHosts.add(this.hostToURI(host));
      } catch (URISyntaxException | MalformedURLException e) {
        logger.log(Level.WARNING, "Invalid host: " + host, e);
      }
    }
    return newHosts;
  }

  private URI nextAsLocalNodesURI() {
    if (this.rack.isEmpty() && this.datacenter.isEmpty()) {
      return nextAsURI("/localnodes", null);
    }
    String query = "";
    if (!this.rack.isEmpty()) {
      query = "rack=" + this.rack;
    }
    if (!this.datacenter.isEmpty()) {
      if (query.isEmpty()) {
        query = "dc=" + this.datacenter;
      } else {
        query += "&dc=" + this.datacenter;
      }
    }
    return nextAsURI("/localnodes", query);
  }

  private static HttpClient prepareHttpClient() {
    RegistryBuilder<ConnectionSocketFactory> socketFactoryRegistryBuilder =
        RegistryBuilder.<ConnectionSocketFactory>create()
            .register("http", PlainConnectionSocketFactory.getSocketFactory())
            .register("https", SSLConnectionSocketFactory.getSocketFactory());

    TrustManager[] trustAllCertificates =
        new TrustManager[] {
          new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }
          }
        };
    try {
      SSLContext sslContext = SSLContext.getInstance("SSL");
      sslContext.init(null, trustAllCertificates, new java.security.SecureRandom());
      socketFactoryRegistryBuilder.register(
          "https", new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE));
    } catch (NoSuchAlgorithmException | KeyManagementException e) {
      throw new RuntimeException(e);
    }

    PoolingHttpClientConnectionManager httpConnectionManager =
        new PoolingHttpClientConnectionManager(socketFactoryRegistryBuilder.build());
    httpConnectionManager.setMaxTotal(200);
    httpConnectionManager.setDefaultMaxPerRoute(1);
    return HttpClients.custom().setConnectionManager(httpConnectionManager).build();
  }

  public static class FailedToCheck extends Exception {
    public FailedToCheck(String message, Throwable cause) {
      super(message, cause);
    }

    public FailedToCheck(String message) {
      super(message);
    }
  }

  /**
   * Validates the server's node list for a specified datacenter and rack. This method checks
   * whether the server returns a non-empty node list for the provided datacenter and rack.
   *
   * @throws com.scylladb.alternator.AlternatorLiveNodes.FailedToCheck if the server cannot be
   *     reached.
   * @throws com.scylladb.alternator.AlternatorLiveNodes.ValidationError if the server returns an
   *     empty node list.
   *     <p>If the server returns a non-empty node list, no exception is thrown.
   * @since 1.0.1
   */
  public void checkIfRackAndDatacenterSetCorrectly() throws FailedToCheck, ValidationError {
    if (this.rack.isEmpty() && this.datacenter.isEmpty()) {
      return;
    }
    try {
      List<URI> nodes = getNodes(nextAsLocalNodesURI());
      if (nodes.isEmpty()) {
        throw new ValidationError(
            "node returned empty list, datacenter or rack are set incorrectly");
      }
    } catch (IOException e) {
      throw new FailedToCheck("failed to read list of nodes from the node", e);
    }
  }

  /**
   * Returns true if remote node supports /localnodes?rack=`rack`&amp;dc=`datacenter`. If it can't
   * conclude by any reason it throws {@link
   * com.scylladb.alternator.AlternatorLiveNodes.FailedToCheck}
   *
   * @return a {@link java.lang.Boolean} object
   * @throws com.scylladb.alternator.AlternatorLiveNodes.FailedToCheck if any.
   * @since 1.0.1
   */
  public Boolean checkIfRackDatacenterFeatureIsSupported() throws FailedToCheck {
    URI uri = nextAsURI("/localnodes", null);
    URI fakeRackUrl;
    try {
      fakeRackUrl =
          new URI(
              uri.getScheme(),
              null,
              uri.getHost(),
              uri.getPort(),
              uri.getPath(),
              "rack=fakeRack",
              null);
    } catch (URISyntaxException e) {
      // Should not ever happen
      throw new FailedToCheck("Invalid URI: " + uri, e);
    }
    try {
      List<URI> hostsWithFakeRack = getNodes(fakeRackUrl);
      List<URI> hostsWithoutRack = getNodes(uri);
      if (hostsWithoutRack.isEmpty()) {
        // This should not normally happen.
        // If list of nodes is empty, it is impossible to conclude if it supports rack/datacenter
        // filtering or not.
        throw new FailedToCheck(String.format("host %s returned empty list", uri));
      }
      // When rack filtering is not supported server returns same nodes.
      return hostsWithFakeRack.size() != hostsWithoutRack.size();
    } catch (IOException e) {
      throw new FailedToCheck("failed to read list of nodes from the node", e);
    }
  }

  /**
   * <p>pickSupportedDatacenterRack.</p>
   *
   * @param seedURI a {@link java.net.URI} object
   * @param datacenter a {@link java.lang.String} object
   * @param rack a {@link java.lang.String} object
   * @return a {@link com.scylladb.alternator.AlternatorLiveNodes} object
   * @since 1.0.3
   */
  public static AlternatorLiveNodes pickSupportedDatacenterRack(
      URI seedURI, String datacenter, String rack) {
    AlternatorLiveNodes liveNodesInstance = new AlternatorLiveNodes(seedURI, datacenter, rack);
    try {
      liveNodesInstance.validate();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    if (datacenter.isEmpty() && rack.isEmpty()) {
      return liveNodesInstance;
    }

    try {
      if (!liveNodesInstance.checkIfRackDatacenterFeatureIsSupported()) {
        logger.log(
            Level.SEVERE,
            String.format(
                "server %s does not support rack or datacenter filtering, fallback to no dc and no rack",
                seedURI));
        return new AlternatorLiveNodes(seedURI, "", "");
      }
    } catch (AlternatorLiveNodes.FailedToCheck e) {
      throw new RuntimeException(e);
    }

    try {
      liveNodesInstance.checkIfRackAndDatacenterSetCorrectly();
      return liveNodesInstance;
    } catch (AlternatorLiveNodes.FailedToCheck e) {
      throw new RuntimeException(e);
    } catch (AlternatorLiveNodes.ValidationError e) {
      if (!rack.isEmpty()) {
        logger.log(
            Level.WARNING,
            String.format(
                "server %s does not know rack `%s` at datacenter `%s`, fallback to no rack",
                seedURI, rack, datacenter));
        return pickSupportedDatacenterRack(seedURI, datacenter, "");
      }
      logger.log(
          Level.WARNING,
          String.format(
              "server %s does not know datacenter `%s`, fallback to no datacenter",
              seedURI, datacenter));
      return new AlternatorLiveNodes(seedURI, "", "");
    }
  }
}
