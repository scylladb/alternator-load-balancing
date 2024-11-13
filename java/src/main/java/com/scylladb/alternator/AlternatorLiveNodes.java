package com.scylladb.alternator;

import java.io.IOException;
import java.net.URI;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Maintains and automatically updates a list of known live Alternator nodes.
 * Live Alternator nodes should answer alternatorScheme (http or https)
 * requests on port alternatorPort. One of these livenodes will be used,
 * at round-robin order, for every connection.  The list of live nodes starts
 * with one or more known nodes, but then a thread periodically replaces this
 * list by an up-to-date list retrieved from making a "/localnodes" requests
 * to one of these nodes.
 */
public class AlternatorLiveNodes extends Thread {
    private final String alternatorScheme;
    private final int alternatorPort;
    private final AtomicReference<List<URI>> liveNodes;
    private final List<URI> initialNodes;
    private final AtomicInteger nextLiveNodeIndex;
    private final String rack;
    private final String datacenter;

    private static Logger logger = Logger.getLogger(AlternatorLiveNodes.class.getName());

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
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    logger.log(Level.INFO, "AlternatorLiveNodes thread interrupted and stopping");
                    return;
                }
            }
        } finally {
            logger.log(Level.INFO, "AlternatorLiveNodes thread stopped");
        }
    }

    public AlternatorLiveNodes(URI liveNode, String datacenter, String rack) {
        this(Collections.singletonList(liveNode), liveNode.getScheme(), liveNode.getPort(), datacenter, rack);
    }

    public AlternatorLiveNodes(List<URI> liveNodes, String scheme, int port, String datacenter, String rack) {
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
    }

    @Override
    public void start() {
        try {
            this.validate();
        } catch (ValidationError e) {
            throw new RuntimeException(e);
        }
        liveNodes.set(initialNodes);

        // setDaemon(true) allows the program to exit even if the thread is still running.
        this.setDaemon(true);
        super.start();
    }

    public void validateURI(URI uri) throws ValidationError {
        try {
            uri.toURL();
        } catch (MalformedURLException e) {
            throw new ValidationError("Invalid URI: " + uri, e);
        }
    }

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

    public URI nextAsURI() {
        List<URI> nodes = liveNodes.get();
        if (nodes.isEmpty()) {
            throw new IllegalStateException("No live nodes available");
        }
        return nodes.get(Math.abs(nextLiveNodeIndex.getAndIncrement() % nodes.size()));
    }

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
    private static String streamToString(java.io.InputStream is) {
        Scanner s = new Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
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
        HttpURLConnection conn;
        conn = (HttpURLConnection) uri.toURL().openConnection();
        try {
            conn.setRequestMethod("GET");
        } catch (ProtocolException e) {
            // It can happen only of conn is already connected or "GET" is not a valid method
            // Both cases not true, os it should happen
            throw new RuntimeException(e);
        }
        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            return Collections.emptyList();
        }
        String response = streamToString(conn.getInputStream());
        // response looks like: ["127.0.0.2","127.0.0.3","127.0.0.1"]
        response = response.trim();
        response = response.substring(1, response.length() - 1);
        String[] list = response.split(",");
        List<URI> newHosts = new ArrayList<>();
        for (String host : list) {
            if (host.isEmpty()){
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

    public static class FailedToCheck extends Exception {
        public FailedToCheck(String message, Throwable cause) {
            super(message, cause);
        }

        public FailedToCheck(String message) {
            super(message);
        }
    }

    /**
     * Checks if server returns non-empty node list for given datacenter/rack.
     * throws {@link FailedToCheck} if it fails to reach server and {@link ValidationError} if list is empty
     * otherwise do not throw
     *
     **/
    public void checkIfRackAndDatacenterSetCorrectly() throws FailedToCheck, ValidationError {
        if (this.rack.isEmpty() && this.datacenter.isEmpty()) {
            return;
        }
        try {
            List<URI> nodes = getNodes(nextAsLocalNodesURI());
            if (nodes.isEmpty()) {
                throw new ValidationError("node returned empty list, datacenter or rack are set incorrectly");
            }
        } catch (IOException e) {
            throw new FailedToCheck("failed to read list of nodes from the node", e);
        }
    }

    /**
     * Returns true if remote node supports /localnodes?rack=<>&dc=<datacenter>.
     * If it can't conclude by any reason it throws {@link FailedToCheck}
     */
    public Boolean checkIfRackDatacenterFeatureIsSupported() throws FailedToCheck {
        URI uri = nextAsURI("/localnodes", null);
        URI fakeRackUrl;
        try {
            fakeRackUrl = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getQuery(), "rack=fakeRack", "");
        } catch (URISyntaxException e) {
            // Should not ever happen
            throw new FailedToCheck("Invalid URI: " + uri, e);
        }
        try {
            List<URI> hostsWithFakeRack = getNodes(fakeRackUrl);
            List<URI> hostsWithoutRack = getNodes(uri);
            if (hostsWithoutRack.isEmpty()) {
                // This should not normally happen.
                // If list of nodes is empty, it is impossible to conclude if it supports rack/datacenter filtering or not.
                throw new FailedToCheck(String.format("host %s returned empty list", uri));
            }
            // When rack filtering is not supported server returns same nodes.
            return hostsWithFakeRack.size() != hostsWithoutRack.size();
        } catch (IOException e) {
            throw new FailedToCheck("failed to read list of nodes from the node", e);
        }
    }
}
