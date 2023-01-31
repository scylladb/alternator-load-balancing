package com.scylladb.alternator;

import static com.scylladb.alternator.AlternatorHttpClient.FAKE_HOST;

import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import software.amazon.awssdk.core.internal.http.loader.DefaultSdkAsyncHttpClientBuilder;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.utils.AttributeMap;

public class AlternatorAsyncHttpClient implements SdkAsyncHttpClient {
    private final AlternatorLiveNodes liveNodes;
    private final SdkAsyncHttpClient base;
    private final int port;
    private final String protocol;

    public AlternatorAsyncHttpClient(SdkAsyncHttpClient base, String protocol,
            String host, int port) {
        liveNodes = AlternatorLiveNodes.create(protocol, Arrays.asList(host),
                port);
        this.base = base;
        this.protocol = protocol;
        this.port = port;
    }

    @Override
    public void close() {
        base.close();
    }

    @Override
    public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
        SdkHttpRequest.Builder b = request.request().toBuilder();
        b.protocol(protocol).host(liveNodes.nextNode()).port(port)
                .putHeader("Host", Arrays.asList(FAKE_HOST));

        AsyncExecuteRequest modified = AsyncExecuteRequest.builder()
                .request(b.build()).fullDuplex(request.fullDuplex())
                .metricCollector(request.metricCollector().orElse(null))
                .requestContentPublisher(request.requestContentPublisher())
                .responseHandler(request.responseHandler()).build();

        return base.execute(modified);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(URI endpoint) {
        return builder().endpoint(endpoint);
    }

    public static final class Builder
            implements SdkAsyncHttpClient.Builder<Builder> {
        private String protocol;
        private String host;
        private int port;

        Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        Builder host(String host) {
            this.host = host;
            return this;
        }

        Builder port(int port) {
            this.port = port;
            return this;
        }

        Builder endpoint(URI endpoint) {
            return protocol(endpoint.getScheme()).host(endpoint.getHost())
                    .port(endpoint.getPort());
        }

        @Override
        public SdkAsyncHttpClient buildWithDefaults(
                AttributeMap serviceDefaults) {
            return new AlternatorAsyncHttpClient(
                    new DefaultSdkAsyncHttpClientBuilder().buildWithDefaults(
                            serviceDefaults),
                    protocol, host, port);
        }
    };

}
