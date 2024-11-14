package com.scylladb.alternator;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URISyntaxException;
import java.net.MalformedURLException;
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

    private final AtomicReference<List<String>> liveNodes;
    private final AtomicInteger nextLiveNodeIndex;

    private static Logger logger = Logger.getLogger(AlternatorLiveNodes.class.getName());

    @Override
    public void run() {
        logger.log(Level.INFO, "AlternatorLiveNodes thread starting");
        for (;;) {
            updateLiveNodes();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.log(Level.INFO, "AlternatorLiveNodes thread interrupted and stopping");
                return;
            }
        }
    }

    // A private constructor. Use the create() functions below instead, as
    // they also start the thread after creating the object.
    private AlternatorLiveNodes(String alternatorScheme, List<String> liveNodes, int alternatorPort) {
        this.alternatorScheme = alternatorScheme;
        this.liveNodes = new AtomicReference<>(new ArrayList<>(liveNodes));
        this.alternatorPort = alternatorPort;
        this.nextLiveNodeIndex = new AtomicInteger(0);
    }

    public static AlternatorLiveNodes create(String scheme, List<String> hosts, int port) {
        AlternatorLiveNodes ret = new AlternatorLiveNodes(scheme, hosts, port);
        // setDaemon(true) allows the program to exit even if the thread is
        // is still running.
        ret.setDaemon(true);
        ret.start();
        // Make sure
        return ret;
    }
    public static AlternatorLiveNodes create(URI uri) {
        return create(uri.getScheme(), Arrays.asList(uri.getHost()), uri.getPort());
    }

    public String nextNode() {
        List<String> nodes = liveNodes.get();
        String node = nodes.get(Math.abs(nextLiveNodeIndex.getAndIncrement() % nodes.size()));
        logger.log(Level.FINE, "Using node " + node);
        return node;
    }

    public URI nextAsURI() {
        try {
            return new URI(alternatorScheme, null, nextNode(), alternatorPort, "", null, null);
        } catch (URISyntaxException e) {
            // Can't happen with the empty path and other nulls we used above...
            logger.log(Level.WARNING, "nextAsURI", e);
            return null;
        }
    }

    public URL nextAsURL(String file) {
        try {
            return new URL(alternatorScheme, nextNode(), alternatorPort, file);
        } catch (MalformedURLException e) {
            // Can only happen if alternatorScheme is an unknown one.
            logger.log(Level.WARNING, "nextAsURL", e);
            return null;
        }
    }

    // Utility function for reading the entire contents of an input stream
    // (which we assume will be fairly short)
    private static String streamToString(java.io.InputStream is) {
        Scanner s = new Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private void updateLiveNodes() {
        List<String> newHosts = new ArrayList<>();
        URL url = nextAsURL("/localnodes");
        try {
            // Note that despite this being called HttpURLConnection, it actually
            // supports HTTPS as well.
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK)  {
                String response = streamToString(conn.getInputStream());
                // response looks like: ["127.0.0.2","127.0.0.3","127.0.0.1"]
                response = response.trim();
                response = response.substring(1, response.length() - 1);
                String[] list = response.split(",");
                for (String host : list) {
                    host = host.trim();
                    host = host.substring(1, host.length() - 1);
                    newHosts.add(host);
                }
            }
        } catch (IOException e) {
            logger.log(Level.FINE, "Request failed: " + url, e);
        }
        if (!newHosts.isEmpty()) {
            liveNodes.set(newHosts);
            logger.log(Level.FINE, "Updated hosts to " + liveNodes);
        }
    }
}
