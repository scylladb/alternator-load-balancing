package com.scylladb.alternator;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.HttpURLConnection;
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
    private final List<String> initialNodes;
    private final AtomicInteger nextLiveNodeIndex;

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

    public AlternatorLiveNodes(String alternatorScheme, List<String> liveNodes, int alternatorPort) {
        this.alternatorScheme = alternatorScheme;
        this.initialNodes = new ArrayList<>(liveNodes);
        this.liveNodes = new AtomicReference<>();
        this.alternatorPort = alternatorPort;
        this.nextLiveNodeIndex = new AtomicInteger(0);
    }

    public AlternatorLiveNodes(URI uri) {
        this(uri.getScheme(), Collections.singletonList(uri.getHost()), uri.getPort());
    }

    @Override
    public void start() {
        List<URI> nodes = new ArrayList<>();
        for (String liveNode : initialNodes) {
            try {
                nodes.add(this.hostToURI(liveNode));
            } catch (URISyntaxException | MalformedURLException e) {
                // Should not happen, initialLiveNodes should be validated at this point
                throw new RuntimeException(e);
            }
        }
        this.liveNodes.set(nodes);

        // setDaemon(true) allows the program to exit even if the thread is still running.
        this.setDaemon(true);
        super.start();
    }

    public void validate() throws ValidationError {
        this.validateConfig();
        for (String liveNode : initialNodes) {
            try {
                this.hostToURI(liveNode);
            } catch (MalformedURLException | URISyntaxException e) {
                throw new ValidationError(String.format("failed to validate initial node %s", liveNode), e);
            }
        }
    }

    public static class ValidationError extends Exception {
        public ValidationError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void validateConfig() throws ValidationError {
        try {
            this.hostToURI("1.1.1.1");
        } catch (MalformedURLException | URISyntaxException e) {
            throw new ValidationError("failed to validate configuration", e);
        }
    }

    private URI hostToURI(String host) throws URISyntaxException, MalformedURLException {
        return hostToURI(host, null, null);
    }

    private URI hostToURI(String host, String path, String query) throws URISyntaxException, MalformedURLException {
        URI uri =  new URI(alternatorScheme, null, host, alternatorPort, path, query, null);
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

    public URI nextAsURI(String file, String query) {
        try {
            URI uri = this.nextAsURI();
            return new URI(uri.getScheme(), null,  uri.getHost(), uri.getPort(), file, query, null);
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
        List<URI> newHosts = new ArrayList<>();
        URI uri = nextAsURI("/localnodes", null);
        // Note that despite this being called HttpURLConnection, it actually
        // supports HTTPS as well.
        HttpURLConnection conn;
        try {
            conn = (HttpURLConnection) uri.toURL().openConnection();
        } catch (MalformedURLException e){
            // Should never happen, uri is already validated at this point
            throw new RuntimeException(e);
        }
        conn.setRequestMethod("GET");
        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            String response = streamToString(conn.getInputStream());
            // response looks like: ["127.0.0.2","127.0.0.3","127.0.0.1"]
            response = response.trim();
            response = response.substring(1, response.length() - 1);
            String[] list = response.split(",");
            for (String host : list) {
                host = host.trim();
                host = host.substring(1, host.length() - 1);
                try {
                    newHosts.add(this.hostToURI(host));
                } catch (URISyntaxException | MalformedURLException e) {
                    logger.log(Level.WARNING, "Invalid host: " + host, e);
                }
            }
        }
        if (!newHosts.isEmpty()) {
            liveNodes.set(newHosts);
            logger.log(Level.FINE, "Updated hosts to " + liveNodes);
        }
    }
}
