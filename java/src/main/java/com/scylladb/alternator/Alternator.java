package com.scylladb.alternator;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.net.URI;
import java.util.function.Consumer;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.AbortableInputStream;
import java.io.IOException;
import java.util.Scanner;
import java.util.Arrays;
import software.amazon.awssdk.utils.AttributeMap;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;

class AlternatorHttpClient implements SdkHttpClient {
    class UpdateThread extends Thread {
        private AlternatorHttpClient owner;
        public UpdateThread(AlternatorHttpClient owner) {
            this.owner = owner;
        }
        public void run() {
            while (!owner.isClosed()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                owner.updateHosts();
            }
            System.out.println("updateThread stopped");
        }
    }

    public static String FAKE_HOST = "dog.scylladb.com";
    public static String LOCALNODES = "/localnodes";
    private SdkHttpClient base;
    private int port;
    private String protocol;
    private volatile int hostIdx = 0;
    private volatile List<String> hosts;
    private volatile boolean closed = false;
    UpdateThread updateThread;

    public AlternatorHttpClient(SdkHttpClient base, String protocol, String host, int port) {
        this.base = base;
        this.protocol = protocol;
        this.hosts = Arrays.asList(host);
        this.port = port;
        updateHosts();
        updateThread = new UpdateThread(this);
        updateThread.start();
    }

    public boolean isClosed() {
        return closed;
    }

    private String nextHost() {
        String host = hosts.get(hostIdx);
        hostIdx = (hostIdx + 1) % hosts.size();
        return host;
    }

    private void updateHosts() {
        List<String> newHosts = new ArrayList<String>();
        try {
            SdkHttpFullRequest req = SdkHttpFullRequest.builder()
                .protocol(protocol)
                .host(hosts.get(0))
                .port(port)
                .encodedPath(LOCALNODES)
                .method(SdkHttpMethod.GET)
                .build();
            ExecutableHttpRequest exec_req = base.prepareRequest(HttpExecuteRequest.builder()
                            .request(req)
                            .build());
            HttpExecuteResponse response = exec_req.call();
            AbortableInputStream body = response.responseBody().get();
            Scanner s = new Scanner(body).useDelimiter("\\A");
            String hosts_string = s.hasNext() ? s.next() : "[]";
            hosts_string = hosts_string.trim();
            hosts_string = hosts_string.substring(1, hosts_string.length() - 1);
            String[] hosts_list = hosts_string.split(",");
            for (String host : hosts_list) {
                host = host.trim();
                host = host.substring(1, host.length() - 1);
                newHosts.add(host);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (!newHosts.isEmpty()) {
            this.hostIdx = 0;
            this.hosts = newHosts;
            System.out.printf("Updated hosts to %s\n", this.hosts.toString());
        }
    }

    @Override
    public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
        SdkHttpFullRequest orig_request = (SdkHttpFullRequest)request.httpRequest();
        SdkHttpFullRequest.Builder builder = orig_request.toBuilder()
                .protocol(protocol)
                .host(nextHost())
                .putHeader("Host", Arrays.asList(FAKE_HOST))
                .port(8000);
        HttpExecuteRequest intercepted_request = HttpExecuteRequest.builder()
                .request(builder.build())
                .contentStreamProvider(orig_request.contentStreamProvider().get())
                .build();
        return base.prepareRequest(intercepted_request);
    }

    @Override
    public void close() {
        closed = true;
        try {
            updateThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        base.close();
    }
}

class AlternatorClient {
    static DynamoDbClientBuilder builder(String protocol, String host, int port) {
        AttributeMap defaults = AttributeMap.builder().put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, true).build();
        return DynamoDbClient.builder()
            .endpointOverride(URI.create("https://" + AlternatorHttpClient.FAKE_HOST))
            .httpClient(new AlternatorHttpClient(AlternatorApacheClient.builder().buildWithDefaults(defaults), protocol, host, port));
    }
}

