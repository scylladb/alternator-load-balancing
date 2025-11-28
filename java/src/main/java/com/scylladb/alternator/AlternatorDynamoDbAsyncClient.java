package com.scylladb.alternator;

import java.net.URI;
import java.util.function.Consumer;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientAsyncConfiguration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.internal.http.loader.DefaultSdkAsyncHttpClientBuilder;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;
import software.amazon.awssdk.services.dynamodb.endpoints.DynamoDbEndpointProvider;
import software.amazon.awssdk.utils.AttributeMap;

/**
 * Factory class for creating DynamoDB async clients with Alternator load balancing support.
 *
 * <p>This class provides a builder that simplifies the construction of an async DynamoDB client
 * (AWS SDK v2) that automatically distributes requests across all nodes in an Alternator
 * cluster. It provides a fluent API compatible with {@link DynamoDbAsyncClient#builder()} while
 * automatically integrating {@link AlternatorEndpointProvider} for client-side load balancing.
 *
 * <p>The builder implements {@link DynamoDbAsyncClientBuilder}, ensuring compatibility with
 * standard AWS SDK v2 patterns while adding Alternator-specific configuration via
 * {@link AlternatorDynamoDbAsyncClientBuilder#withAlternatorConfig(AlternatorConfig)}.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // With datacenter/rack filtering
 * AlternatorConfig config = AlternatorConfig.builder()
 *     .withDatacenter("dc1")
 *     .withRack("rack1")
 *     .build();
 *
 * DynamoDbAsyncClient client = AlternatorDynamoDbAsyncClient.builder()
 *     .endpointOverride(URI.create("https://localhost:8043"))
 *     .credentialsProvider(credentialsProvider)
 *     .withAlternatorConfig(config)
 *     .build();
 *
 * // Without datacenter/rack filtering
 * DynamoDbAsyncClient client = AlternatorDynamoDbAsyncClient.builder()
 *     .endpointOverride(URI.create("https://localhost:8043"))
 *     .credentialsProvider(credentialsProvider)
 *     .build();
 * }</pre>
 *
 * @author dmitry.kropachev
 * @since 1.0.5
 */
public class AlternatorDynamoDbAsyncClient {

  /**
   * Creates a new builder instance using the standard builder pattern, similar to
   * {@link DynamoDbAsyncClient#builder()}.
   *
   * @return a new {@link AlternatorDynamoDbAsyncClientBuilder} configured for Alternator load
   *     balancing
   */
  public static AlternatorDynamoDbAsyncClientBuilder builder() {
    return new AlternatorDynamoDbAsyncClientBuilder();
  }

  /**
   * Builder implementation for constructing async DynamoDB clients with Alternator load balancing.
   *
   * <p>This builder implements {@link DynamoDbAsyncClientBuilder} and delegates most configuration
   * to the standard AWS SDK {@link DynamoDbAsyncClient} builder while automatically integrating
   * {@link AlternatorEndpointProvider} for node discovery and load balancing.
   *
   * <p>The builder tracks the seed URI (via {@link #endpointOverride(URI)}) and optional
   * datacenter/rack configuration, then creates an {@link AlternatorEndpointProvider} during the
   * {@link #build()} phase.
   *
   * <p>Note: Some AWS-specific features are not supported by Alternator and will throw
   * {@link UnsupportedOperationException}, including endpoint discovery, FIPS mode, and dual-stack
   * networking.
   */
  public static class AlternatorDynamoDbAsyncClientBuilder implements DynamoDbAsyncClientBuilder {
    private final DynamoDbAsyncClientBuilder delegate;
    private AlternatorConfig alternatorConfig;
    private URI seedUri;
    private Region region;
    private boolean disableCertificateChecks = false;
    private boolean httpClientSet = false;

    private AlternatorDynamoDbAsyncClientBuilder() {
      this.delegate = DynamoDbAsyncClient.builder();
    }

    /**
     * Sets the Alternator configuration including datacenter and rack settings.
     *
     * <p>When datacenter and/or rack are specified, the load balancer will only use nodes from the
     * specified datacenter/rack combination. This is useful for:
     *
     * <ul>
     *   <li>Reducing cross-datacenter latency by connecting only to local nodes
     *   <li>Isolating traffic to specific racks for testing or capacity management
     * </ul>
     *
     * <p>If the server doesn't support datacenter/rack filtering, or if the specified
     * datacenter/rack doesn't exist, the configuration will gracefully fall back to using all
     * available nodes.
     *
     * @param config the Alternator configuration
     * @return this builder instance
     */
    public AlternatorDynamoDbAsyncClientBuilder withAlternatorConfig(AlternatorConfig config) {
      this.alternatorConfig = config;
      return this;
    }

    /**
     * Disables SSL certificate validation for testing purposes.
     *
     * <p><strong>WARNING:</strong> This should only be used for testing with self-signed
     * certificates. Never use this in production as it makes connections vulnerable to
     * man-in-the-middle attacks.
     *
     * <p>This method configures the HTTP client to trust all certificates. If you've already
     * set a custom HTTP client via {@link #httpClient(SdkAsyncHttpClient)} or
     * {@link #httpClientBuilder(SdkAsyncHttpClient.Builder)}, this method will have no effect.
     *
     * @return this builder instance
     */
    public AlternatorDynamoDbAsyncClientBuilder withDisableCertificateChecks() {
      this.disableCertificateChecks = true;
      return this;
    }

    /**
     * Sets the AWS region. This method matches {@link DynamoDbAsyncClientBuilder#region(Region)}.
     *
     * <p>Note: The region is not used by Alternator for routing (the endpoint provider handles
     * that), but it is required by the AWS SDK and may appear in logs, traces, or metrics. If not
     * specified, a default "fake-aws-region" will be used.
     *
     * @param region the AWS region
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbAsyncClientBuilder region(Region region) {
      this.region = region;
      delegate.region(region);
      return this;
    }

    /**
     * Sets the AWS credentials provider. This method matches
     * {@link DynamoDbAsyncClientBuilder#credentialsProvider(AwsCredentialsProvider)}.
     *
     * <p>The credentials are used for authentication with Alternator. Common providers include:
     *
     * <ul>
     *   <li>{@link software.amazon.awssdk.auth.credentials.StaticCredentialsProvider} for hardcoded
     *       credentials
     *   <li>{@link software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider} for
     *       environment-based credentials
     * </ul>
     *
     * @param credentialsProvider the AWS credentials provider
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbAsyncClientBuilder credentialsProvider(
        AwsCredentialsProvider credentialsProvider) {
      delegate.credentialsProvider(credentialsProvider);
      return this;
    }

    /**
     * Sets the endpoint override (seed URI) for Alternator cluster discovery.
     *
     * <p>This is the initial Alternator node that will be contacted to discover all other nodes in
     * the cluster via the {@code /localnodes} API. Once the cluster topology is discovered,
     * requests will be distributed across all discovered nodes using round-robin load balancing.
     *
     * <p>The endpoint should be a complete URI including protocol and port, for example:
     *
     * <ul>
     *   <li>{@code https://localhost:8043} for HTTPS
     *   <li>{@code http://192.168.1.100:8000} for HTTP
     * </ul>
     *
     * <p>This method is required - the build will fail if no endpoint is specified.
     *
     * @param endpointOverride the seed URI for Alternator cluster discovery
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbAsyncClientBuilder endpointOverride(URI endpointOverride) {
      this.seedUri = endpointOverride;
      return this;
    }

    /**
     * Sets a custom async HTTP client. This method matches
     * {@link DynamoDbAsyncClientBuilder#httpClient(SdkAsyncHttpClient)}.
     *
     * <p>Use this to configure custom HTTP client settings such as connection pooling, timeouts, or
     * proxy settings. If not specified, the AWS SDK will create a default HTTP client.
     *
     * @param httpClient the async HTTP client to use
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbAsyncClientBuilder httpClient(SdkAsyncHttpClient httpClient) {
      this.httpClientSet = true;
      delegate.httpClient(httpClient);
      return this;
    }

    /**
     * Sets a custom async HTTP client builder. This method matches
     * {@link DynamoDbAsyncClientBuilder#httpClientBuilder(SdkAsyncHttpClient.Builder)}.
     *
     * <p>Use this to configure HTTP client settings using a builder pattern. This is an alternative
     * to {@link #httpClient(SdkAsyncHttpClient)} when you want to customize the HTTP client
     * configuration without creating the client instance directly.
     *
     * @param httpClientBuilder the async HTTP client builder to use
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbAsyncClientBuilder httpClientBuilder(
        SdkAsyncHttpClient.Builder httpClientBuilder) {
      this.httpClientSet = true;
      delegate.httpClientBuilder(httpClientBuilder);
      return this;
    }

    /**
     * Returns the current client override configuration.
     *
     * @return the current {@link ClientOverrideConfiguration}, or null if not set
     */
    @Override
    public ClientOverrideConfiguration overrideConfiguration() {
      return delegate.overrideConfiguration();
    }

    /**
     * Sets client override configuration. This method matches
     * {@link DynamoDbAsyncClientBuilder#overrideConfiguration(ClientOverrideConfiguration)}.
     *
     * <p>Use this to configure advanced client settings such as:
     *
     * <ul>
     *   <li>Retry policies
     *   <li>Request timeout settings
     *   <li>Additional request headers
     *   <li>Metric publishers
     * </ul>
     *
     * @param overrideConfiguration the client override configuration
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbAsyncClientBuilder overrideConfiguration(
        ClientOverrideConfiguration overrideConfiguration) {
      delegate.overrideConfiguration(overrideConfiguration);
      return this;
    }

    /**
     * Sets client override configuration using a builder consumer. This method matches
     * {@link DynamoDbAsyncClientBuilder#overrideConfiguration(Consumer)}.
     *
     * <p>This is a convenience method that allows configuring the override settings inline without
     * creating a separate {@link ClientOverrideConfiguration} instance.
     *
     * <p>Example:
     *
     * <pre>{@code
     * builder.overrideConfiguration(c -> c
     *     .apiCallTimeout(Duration.ofSeconds(10))
     *     .retryPolicy(RetryPolicy.builder().numRetries(3).build())
     * )
     * }</pre>
     *
     * @param builderConsumer a consumer that configures the override configuration builder
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbAsyncClientBuilder overrideConfiguration(
        Consumer<ClientOverrideConfiguration.Builder> builderConsumer) {
      delegate.overrideConfiguration(builderConsumer);
      return this;
    }

    /**
     * Sets async client configuration. This method matches
     * {@link DynamoDbAsyncClientBuilder#asyncConfiguration(ClientAsyncConfiguration)}.
     *
     * <p>Use this to configure async-specific settings such as the future completion executor.
     *
     * @param asyncConfiguration the async client configuration
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbAsyncClientBuilder asyncConfiguration(
        ClientAsyncConfiguration asyncConfiguration) {
      delegate.asyncConfiguration(asyncConfiguration);
      return this;
    }

    /**
     * Sets async client configuration using a builder consumer. This method matches
     * {@link DynamoDbAsyncClientBuilder#asyncConfiguration(Consumer)}.
     *
     * @param builderConsumer a consumer that configures the async configuration builder
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbAsyncClientBuilder asyncConfiguration(
        Consumer<ClientAsyncConfiguration.Builder> builderConsumer) {
      delegate.asyncConfiguration(builderConsumer);
      return this;
    }

    /**
     * This method is not supported by AlternatorDynamoDbAsyncClient.
     *
     * <p>The endpoint provider is automatically configured to use {@link AlternatorEndpointProvider}
     * for load balancing. Use {@link #endpointOverride(URI)} to specify the seed endpoint instead.
     *
     * @param endpointProvider ignored
     * @return never returns
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    public AlternatorDynamoDbAsyncClientBuilder endpointProvider(
        DynamoDbEndpointProvider endpointProvider) {
      throw new UnsupportedOperationException(
          "AlternatorDynamoDbAsyncClient does not support custom endpoint providers. "
              + "Use endpointOverride(URI) to specify the seed endpoint instead.");
    }

    /**
     * This method is not supported by AlternatorDynamoDbAsyncClient.
     *
     * <p>Alternator uses its own node discovery mechanism via the {@code /localnodes} API, which is
     * incompatible with AWS endpoint discovery.
     *
     * @param endpointDiscoveryEnabled ignored
     * @return never returns
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    public AlternatorDynamoDbAsyncClientBuilder endpointDiscoveryEnabled(
        boolean endpointDiscoveryEnabled) {
      throw new UnsupportedOperationException(
          "AlternatorDynamoDbAsyncClient does not support AWS endpoint discovery. "
              + "Node discovery is handled automatically via the /localnodes API.");
    }

    /**
     * This method is not supported by AlternatorDynamoDbAsyncClient.
     *
     * <p>Alternator uses its own node discovery mechanism via the {@code /localnodes} API, which is
     * incompatible with AWS endpoint discovery.
     *
     * @return never returns
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    public AlternatorDynamoDbAsyncClientBuilder enableEndpointDiscovery() {
      throw new UnsupportedOperationException(
          "AlternatorDynamoDbAsyncClient does not support AWS endpoint discovery. "
              + "Node discovery is handled automatically via the /localnodes API.");
    }

    /**
     * This method is not supported by AlternatorDynamoDbAsyncClient.
     *
     * <p>FIPS (Federal Information Processing Standards) mode is an AWS-specific feature that is not
     * applicable to Alternator.
     *
     * @param fipsEnabled ignored
     * @return never returns
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    public AlternatorDynamoDbAsyncClientBuilder fipsEnabled(Boolean fipsEnabled) {
      throw new UnsupportedOperationException(
          "AlternatorDynamoDbAsyncClient does not support FIPS mode (AWS-specific feature).");
    }

    /**
     * This method is not supported by AlternatorDynamoDbAsyncClient.
     *
     * <p>Dual-stack networking (IPv4/IPv6) is an AWS-specific feature that is not applicable to
     * Alternator.
     *
     * @param dualstackEnabled ignored
     * @return never returns
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    public AlternatorDynamoDbAsyncClientBuilder dualstackEnabled(Boolean dualstackEnabled) {
      throw new UnsupportedOperationException(
          "AlternatorDynamoDbAsyncClient does not support dual-stack networking (AWS-specific feature).");
    }

    /**
     * Builds and returns an async DynamoDB client with Alternator load balancing configured.
     *
     * <p>This method performs the following steps:
     *
     * <ol>
     *   <li>Validates that {@link #endpointOverride(URI)} was called (required)
     *   <li>Initializes {@link AlternatorConfig} with default values if not configured
     *   <li>Creates an {@link AlternatorEndpointProvider} with the seed URI and DC/rack settings
     *   <li>Sets a default region ("fake-aws-region") if none was specified
     *   <li>Builds the underlying {@link DynamoDbAsyncClient} with all configurations applied
     * </ol>
     *
     * <p>The returned client will automatically:
     *
     * <ul>
     *   <li>Discover all nodes in the Alternator cluster via the {@code /localnodes} API
     *   <li>Distribute requests across discovered nodes using round-robin load balancing
     *   <li>Periodically refresh the node list (every 5 seconds) to handle topology changes
     *   <li>Filter nodes by datacenter/rack if configured via {@link #withAlternatorConfig}
     * </ul>
     *
     * @return a {@link DynamoDbAsyncClient} instance configured with Alternator load balancing
     * @throws IllegalStateException if {@link #endpointOverride(URI)} was not called
     */
    @Override
    public DynamoDbAsyncClient build() {
      if (seedUri == null) {
        throw new IllegalStateException(
            "endpointOverride must be set when using AlternatorDynamoDbAsyncClientBuilder. "
                + "Call endpointOverride(URI) with the seed Alternator node URI.");
      }

      // Initialize alternatorConfig with defaults if null
      if (alternatorConfig == null) {
        alternatorConfig = AlternatorConfig.builder().build();
      }

      // Configure async HTTP client to disable certificate checking if requested and no custom client was set
      if (disableCertificateChecks && !httpClientSet) {
        SdkAsyncHttpClient httpClient =
            new DefaultSdkAsyncHttpClientBuilder()
                .buildWithDefaults(
                    AttributeMap.builder()
                        .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, true)
                        .build());
        delegate.httpClient(httpClient);
      } else {
        throw new IllegalStateException("you can't override client and have disableCertificateChecks at the same time");
      }

      // Create AlternatorEndpointProvider with the seed URI and DC/rack settings
      AlternatorEndpointProvider alternatorEndpointProvider =
          new AlternatorEndpointProvider(
              seedUri, alternatorConfig.getDatacenter(), alternatorConfig.getRack());

      // Set the endpoint provider on the delegate
      delegate.endpointProvider(alternatorEndpointProvider);

      // Set default region if not specified (required by AWS SDK but not used by Alternator)
      if (region == null) {
        delegate.region(Region.of("fake-aws-region"));
      }

      return delegate.build();
    }
  }
}
