package com.scylladb.alternator.sdkv2;

import com.scylladb.alternator.AlternatorEndpointProvider;
import com.scylladb.alternator.common.AlternatorConfig;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.endpoints.EndpointProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.endpoints.DynamoDbEndpointProvider;

/**
 * Builder for creating DynamoDB clients with Alternator load balancing support.
 * This builder provides the same API as {@link DynamoDbClient#builder()} but automatically
 * integrates {@link AlternatorEndpointProvider} for client-side load balancing across
 * multiple Alternator nodes.
 *
 * <p>Example usage:
 * <pre>
 * // Using AlternatorConfig (recommended)
 * AlternatorConfig config = AlternatorConfig.builder()
 *     .withDatacenter("dc1")
 *     .withRack("rack1")
 *     .build();
 *
 * DynamoDbClient client = AlternatorDynamoDbClient.builder()
 *     .endpointOverride(URI.create("https://localhost:8043"))
 *     .credentialsProvider(credentialsProvider)
 *     .withAlternatorConfig(config)
 *     .build();
 *
 * // Using individual methods (backward compatible)
 * DynamoDbClient client = AlternatorDynamoDbClient.builder()
 *     .endpointOverride(URI.create("https://localhost:8043"))
 *     .credentialsProvider(credentialsProvider)
 *     .withDatacenter("dc1")
 *     .withRack("rack1")
 *     .build();
 * </pre>
 *
 * @author dmitry.kropachev
 * @since 1.0.5
 */
public class AlternatorDynamoDbClient {

  /**
   * Creates a new builder instance.
   *
   * @return a new {@link AlternatorDynamoDbClientBuilder}
   */
  public static AlternatorDynamoDbClientBuilder builder() {
    return new AlternatorDynamoDbClientBuilder();
  }

  public static class AlternatorDynamoDbClientBuilder implements DynamoDbClientBuilder {
    private final DynamoDbClientBuilder delegate;
    private AlternatorConfig alternatorConfig;
    private URI seedUri;
    private Region region;

    private AlternatorDynamoDbClientBuilder() {
      this.delegate = DynamoDbClient.builder();
    }

    /**
     * Sets the Alternator configuration for datacenter and rack filtering.
     *
     * @param config the Alternator configuration
     * @return this builder instance
     */
    public AlternatorDynamoDbClientBuilder withAlternatorConfig(AlternatorConfig config) {
      this.alternatorConfig = config;
      return this;
    }

    @Override
    public AlternatorDynamoDbClientBuilder region(Region region) {
      this.region = region;
      delegate.region(region);
      return this;
    }

    @Override
    public AlternatorDynamoDbClientBuilder credentialsProvider(AwsCredentialsProvider credentialsProvider) {
      delegate.credentialsProvider(credentialsProvider);
      return this;
    }

    @Override
    public AlternatorDynamoDbClientBuilder endpointOverride(URI endpointOverride) {
      this.seedUri = endpointOverride;
      return this;
    }

    @Override
    public AlternatorDynamoDbClientBuilder httpClient(SdkHttpClient httpClient) {
      delegate.httpClient(httpClient);
      return this;
    }

    @Override
    public AlternatorDynamoDbClientBuilder httpClientBuilder(SdkHttpClient.Builder httpClientBuilder) {
      delegate.httpClientBuilder(httpClientBuilder);
      return this;
    }

    @Override
    public ClientOverrideConfiguration overrideConfiguration() {
      return delegate.overrideConfiguration();
    }

    @Override
    public AlternatorDynamoDbClientBuilder overrideConfiguration(ClientOverrideConfiguration overrideConfiguration) {
      delegate.overrideConfiguration(overrideConfiguration);
      return this;
    }

    @Override
    public AlternatorDynamoDbClientBuilder overrideConfiguration(Consumer<ClientOverrideConfiguration.Builder> builderConsumer) {
      delegate.overrideConfiguration(builderConsumer);
      return this;
    }

    @Override
    public AlternatorDynamoDbClientBuilder endpointProvider(DynamoDbEndpointProvider endpointProvider) {
      throw new UnsupportedOperationException("AlternatorDynamoDbClient does not support endpointProvider");
    }

    @Override
    public AlternatorDynamoDbClientBuilder endpointDiscoveryEnabled(boolean endpointDiscoveryEnabled) {
      throw new UnsupportedOperationException("AlternatorDynamoDbClient does not support endpointDiscovery");
    }

    @Override
    public AlternatorDynamoDbClientBuilder enableEndpointDiscovery() {
      throw new UnsupportedOperationException("AlternatorDynamoDbClient does not support endpointDiscovery");
    }

    @Override
    public AlternatorDynamoDbClientBuilder fipsEnabled(Boolean fipsEnabled) {
      throw new UnsupportedOperationException("AlternatorDynamoDbClient does not support fips");
    }

    @Override
    public AlternatorDynamoDbClientBuilder dualstackEnabled(Boolean dualstackEnabled) {
      throw new UnsupportedOperationException("AlternatorDynamoDbClient does not support dual stack");
    }

    @Override
    public DynamoDbClient build() {
      if (seedUri == null) {
        throw new IllegalStateException("endpointOverride must be set when using AlternatorDynamoDbClientBuilder");
      }

      // Create AlternatorEndpointProvider with the seed URI and DC/rack settings
      AlternatorEndpointProvider alternatorEndpointProvider =
          new AlternatorEndpointProvider(seedUri, alternatorConfig.getDatacenter(), alternatorConfig.getRack());

      // Set the endpoint provider on the delegate and build
      delegate.endpointProvider(alternatorEndpointProvider);

      if (region == null) {
        delegate.region(Region.of("fake-aws-region"));
      }

      return delegate.build();
    }
  }
}