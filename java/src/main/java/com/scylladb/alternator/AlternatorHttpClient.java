package com.scylladb.alternator;

import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import java.util.Arrays;
import java.util.logging.Logger;
import java.util.logging.Level;

class AlternatorHttpClient implements SdkHttpClient {
    AlternatorLiveNodes liveNodes;
    private static Logger logger = Logger.getLogger(AlternatorHttpClient.class.getName());

    public static String FAKE_HOST = "dog.scylladb.com";
    public static String LOCALNODES = "/localnodes";
    private SdkHttpClient base;
    private int port;
    private String protocol;

    public AlternatorHttpClient(SdkHttpClient base, String protocol, String host, int port) {
        liveNodes = AlternatorLiveNodes.create(protocol, Arrays.asList(host), port);
        this.base = base;
        this.protocol = protocol;
        this.port = port;
    }

    @Override
    public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
        SdkHttpFullRequest orig_request = (SdkHttpFullRequest)request.httpRequest();
        SdkHttpFullRequest.Builder builder = orig_request.toBuilder()
                .protocol(protocol)
                .host(liveNodes.nextNode())
                .putHeader("Host", Arrays.asList(FAKE_HOST))
                .port(port);
        HttpExecuteRequest intercepted_request = HttpExecuteRequest.builder()
                .request(builder.build())
                .contentStreamProvider(orig_request.contentStreamProvider().get())
                .build();
        return base.prepareRequest(intercepted_request);
    }

    // We are forced to override this method to appease the interface
    // software.amazon.awssdk.utils.SdkAutoCloseable, but it's never
    // actually closed...
    @Override
    public void close() {
        base.close();
    }
}
