package com.scylladb.alternator;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import java.net.URI;
import software.amazon.awssdk.utils.AttributeMap;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;

public class AlternatorClient {
    public static DynamoDbClientBuilder builder(String protocol, String host, int port) {
        // FIXME: disabling SSL certificate check should be part of the
        // demo, not part of the library!
        AttributeMap defaults = AttributeMap.builder()
            .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, true)
            .build();
        return DynamoDbClient.builder()
            .endpointOverride(URI.create("https://" + AlternatorHttpClient.FAKE_HOST))
            .httpClient(new AlternatorHttpClient(AlternatorApacheClient.builder().buildWithDefaults(defaults), protocol, host, port));
    }
}
