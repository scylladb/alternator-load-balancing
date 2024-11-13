package com.scylladb.alternator;

import org.joda.time.LocalDateTime;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.Arrays;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URISyntaxException;
import java.net.MalformedURLException;
import java.net.HttpURLConnection;
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
    private String alternatorScheme;
    private int alternatorPort;

    private List<String> liveNodes;
    private int nextLiveNodeIndex;
    private String rack;
    private String datacenter;
    private Boolean supportsRackAndDatacenter;
    private LocalDateTime nextRackAndDatacenterFeatureSniffTime;

    private static Logger logger = Logger.getLogger(AlternatorLiveNodes.class.getName());

    @Override
    public void run() {
        logger.log(Level.INFO, "AlternatorLiveNodes thread starting");
        for (; ; ) {
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
        this.liveNodes = liveNodes;
        this.alternatorPort = alternatorPort;
        this.nextLiveNodeIndex = 0;
    }

    public static AlternatorLiveNodes create(String scheme, List<String> hosts, int port) {
        AlternatorLiveNodes ret = new AlternatorLiveNodes(scheme, hosts, port);
        // setDaemon(true) allows the program to exit even if the thread is
        // is still running.
        ret.setDaemon(true);
        // Make sure
        return ret;
    }

    public static AlternatorLiveNodes create(URI uri) {
        return create(uri.getScheme(), Arrays.asList(uri.getHost()), uri.getPort());
    }

    public synchronized String nextNode() {
        String node = liveNodes.get(nextLiveNodeIndex);
        logger.log(Level.FINE, "Using node " + node);
        nextLiveNodeIndex = (nextLiveNodeIndex + 1) % liveNodes.size();
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

    private boolean isRackAndDatacenterSupported() {
        if (this.supportsRackAndDatacenter == null) {
            LocalDateTime now = LocalDateTime.now();
            if (this.nextRackAndDatacenterFeatureSniffTime == null || now.isAfter(this.nextRackAndDatacenterFeatureSniffTime)) {
                this.nextRackAndDatacenterFeatureSniffTime = now.plusMinutes(1);
                try {
                    this.supportsRackAndDatacenter = this.sniffIfRackDatacenterFeatureIsSupported();
                } catch (EmptyNodesListException e) {
                    logger.log(Level.WARNING, "rack/datacenter node filtering feature: failed to sniff because server returned no nodes");
                } catch (IOException e) {
                    logger.log(Level.WARNING, "rack/datacenter node filtering feature: sniffing failed", e);
                }

                if (this.supportsRackAndDatacenter != null) {
                    if (this.supportsRackAndDatacenter) {
                        logger.log(Level.INFO, "rack/datacenter node filtering feature: is supported");
                    } else {
                        logger.log(Level.INFO, "rack/datacenter node filtering feature: is not supported, server does not recognize rack parameter");
                    }
                }
            }
        }

        if (this.supportsRackAndDatacenter == null) {
            return false;
        } else return this.supportsRackAndDatacenter;
    }

    private boolean isRackSpecified() {
        return this.rack != null && !this.rack.isEmpty();
    }

    public void setRack(String rack) {
        this.rack = rack;
    }

    private boolean isDatacenterSpecified() {
        return this.datacenter != null && !this.datacenter.isEmpty();
    }

    public void setDatacenter(String datacenter) {
        this.datacenter = datacenter;
    }

    private URL nextAsLocalNodesURL() throws MalformedURLException {
        if (!this.isRackAndDatacenterSupported() || (!this.isRackSpecified() && !this.isDatacenterSpecified())) {
            return nextAsURL("/localnodes");
        }
        String path = "/localnodes";
        boolean paramsInitiated = false;
        if (this.isRackSpecified()) {
            path += "?rack=" + this.rack;
            paramsInitiated = true;
        }
        if (this.isDatacenterSpecified()) {
            if (paramsInitiated) {
                path += "&dc=" + this.datacenter;
            } else {
                path += "?dc=" + this.datacenter;
            }
        }
        return nextAsURL(path);
    }

    private URL nextAsURL(String file) throws MalformedURLException {
        return new URL(alternatorScheme, nextNode(), alternatorPort, file);
    }

    public RuntimeException checkIfRackAndDatacenterSetCorrectly() {
        if (!this.isRackSpecified() && !this.isDatacenterSpecified()) {
            return null;
        }
        try {
            this.supportsRackAndDatacenter = this.sniffIfRackDatacenterFeatureIsSupported();
        } catch (EmptyNodesListException e) {
            RuntimeException ee = new RuntimeException("rack/datacenter node filtering feature: failed to sniff because server returned no nodes");
            ee.addSuppressed(e);
            return ee;
        } catch (IOException e) {
            RuntimeException ee = new RuntimeException("rack/datacenter node filtering feature: sniffing failed");
            ee.addSuppressed(e);
            return ee;
        }
        if (this.supportsRackAndDatacenter) {
            try {
                List<String> nodes = getNodes(nextAsLocalNodesURL());
                if (nodes.isEmpty()) {
                    return new RuntimeException("rack/datacenter node filtering feature: node returned empty list, datacenter or rack are set incorrectly");
                }
                return null;
            } catch (IOException e) {
                return new RuntimeException(e);
            }
        }
        return new RuntimeException("rack/datacenter node filtering feature: is not supported, server does not recognize rack parameter");
    }

    // Utility function for reading the entire contents of an input stream
    // (which we assume will be fairly short)
    private static String streamToString(java.io.InputStream is) {
        Scanner s = new Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private List<String> getNodes(URL url) throws IOException {
        List<String> newHosts = new ArrayList<String>();
        // Note that despite this being called HttpURLConnection, it actually
        // supports HTTPS as well.
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            String response = streamToString(conn.getInputStream());
            // response looks like: ["127.0.0.2","127.0.0.3","127.0.0.1"]
            response = response.trim();
            response = response.substring(1, response.length() - 1);
            String[] list = response.split(",");
            for (String host : list) {
                if (host.isEmpty()) {
                    continue;
                }
                host = host.trim();
                host = host.substring(1, host.length() - 1);
                newHosts.add(host);
            }
        }
        return newHosts;
    }

    private Boolean sniffIfRackDatacenterFeatureIsSupported() throws IOException {
        URL url = nextAsURL("/localnodes");
        URL fakeRackUrl = new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "?rack=fakeRack");
        List<String> hostsWithFakeRack = getNodes(fakeRackUrl);
        List<String> hostsWithoutRack = getNodes(url);
        if (hostsWithoutRack.isEmpty()) {
            // If list of nodes is empty, it is impossible to conclude if it supports rack/datacenter filtering or not.
            throw new EmptyNodesListException(url);
        }

        Collections.sort(hostsWithFakeRack);
        Collections.sort(hostsWithoutRack);
        if (hostsWithoutRack.equals(hostsWithFakeRack)) {
            // When rack filtering is not supported server returns same nodes.
            return false;
        }
        return true;
    }

    private void updateLiveNodes() {
        List<String> newHosts;
        URL url;
        try {
            url = nextAsLocalNodesURL();
        } catch (MalformedURLException e) {
            // Can only happen if alternatorScheme is an unknown one.
            logger.log(Level.WARNING, "nextAsLocalNodesURL", e);
            return;
        }

        try {
            newHosts = getNodes(url);
        } catch (IOException e) {
            logger.log(Level.FINE, "Request failed: " + url, e);
            return;
        }
        if (!newHosts.isEmpty()) {
            synchronized (this) {
                this.liveNodes = newHosts;
            }
            logger.log(Level.FINE, "Updated hosts to " + this.liveNodes);
        }
    }

    static class EmptyNodesListException extends IOException {
        public EmptyNodesListException(URL url) {
            super(String.format("Server %s returned empty node list", url));
        }
    }
}
