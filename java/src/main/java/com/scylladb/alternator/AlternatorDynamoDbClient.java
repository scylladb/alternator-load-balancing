package com.scylladb.alternator;

import java.net.URI;
import java.util.function.Consumer;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.endpoints.DynamoDbEndpointProvider;
import software.amazon.awssdk.utils.AttributeMap;

/**
 * Factory class for creating DynamoDB clients with Alternator load balancing support.
 *
 * <p>This class provides a builder that simplifies the construction of a DynamoDB client
 * (AWS SDK v2) that automatically distributes requests across all nodes in an Alternator
 * cluster. It provides a fluent API compatible with {@link DynamoDbClient#builder()} while
 * automatically integrating {@link AlternatorEndpointProvider} for client-side load balancing.
 *
 * <p>The builder implements {@link DynamoDbClientBuilder}, ensuring compatibility with standard
 * AWS SDK v2 patterns while adding Alternator-specific configuration via
 * {@link AlternatorDynamoDbClientBuilder#withAlternatorConfig(AlternatorConfig)}.
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
 * DynamoDbClient client = AlternatorDynamoDbClient.builder()
 *     .endpointOverride(URI.create("https://localhost:8043"))
 *     .credentialsProvider(credentialsProvider)
 *     .withAlternatorConfig(config)
 *     .build();
 *
 * // Without datacenter/rack filtering
 * DynamoDbClient client = AlternatorDynamoDbClient.builder()
 *     .endpointOverride(URI.create("https://localhost:8043"))
 *     .credentialsProvider(credentialsProvider)
 *     .build();
 * }</pre>
 *
 * @author dmitry.kropachev
 * @since 1.0.5
 */
public class AlternatorDynamoDbClient {

  /**
   * Creates a new builder instance using the standard builder pattern, similar to
   * {@link DynamoDbClient#builder()}.
   *
   * @return a new {@link AlternatorDynamoDbClientBuilder} configured for Alternator load balancing
   */
  public static AlternatorDynamoDbClientBuilder builder() {
    return new AlternatorDynamoDbClientBuilder();
  }

  /**
   * Builder implementation for constructing DynamoDB clients with Alternator load balancing.
   *
   * <p>This builder implements {@link DynamoDbClientBuilder} and delegates most configuration
   * to the standard AWS SDK {@link DynamoDbClient} builder while automatically integrating
   * {@link AlternatorEndpointProvider} for node discovery and load balancing.
   *
   * <p>The builder tracks the seed URI (via {@link #endpointOverride(URI)}) and optional
   * datacenter/rack configuration, then creates an {@link AlternatorEndpointProvider} during
   * the {@link #build()} phase.
   *
   * <p>Note: Some AWS-specific features are not supported by Alternator and will throw
   * {@link UnsupportedOperationException}, including endpoint discovery, FIPS mode, and
   * dual-stack networking.
   */
  public static class AlternatorDynamoDbClientBuilder implements DynamoDbClientBuilder {
    private final DynamoDbClientBuilder delegate;
    private AlternatorConfig alternatorConfig;
    private URI seedUri;
    private Region region;
    private boolean disableCertificateChecks = false;
    private boolean httpClientSet = false;

    private AlternatorDynamoDbClientBuilder() {
      this.delegate = DynamoDbClient.builder();
    }

    /**
     * Sets the Alternator configuration including datacenter and rack settings.
     *
     * <p>When datacenter and/or rack are specified, the load balancer will only use nodes
     * from the specified datacenter/rack combination. This is useful for:
     * <ul>
     *   <li>Reducing cross-datacenter latency by connecting only to local nodes</li>
     *   <li>Isolating traffic to specific racks for testing or capacity management</li>
     * </ul>
     *
     * <p>If the server doesn't support datacenter/rack filtering, or if the specified
     * datacenter/rack doesn't exist, the configuration will gracefully fall back to using
     * all available nodes.
     *
     * @param config the Alternator configuration
     * @return this builder instance
     */
    public AlternatorDynamoDbClientBuilder withAlternatorConfig(AlternatorConfig config) {
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
     * set a custom HTTP client via {@link #httpClient(SdkHttpClient)} or
     * {@link #httpClientBuilder(SdkHttpClient.Builder)}, this method will have no effect.
     *
     * @return this builder instance
     */
    public AlternatorDynamoDbClientBuilder withDisableCertificateChecks() {
      this.disableCertificateChecks = true;
      return this;
    }

    /**
     * Sets the AWS region. This method matches {@link DynamoDbClientBuilder#region(Region)}.
     *
     * <p>Note: The region is not used by Alternator for routing (the endpoint provider handles
     * that), but it is required by the AWS SDK and may appear in logs, traces, or metrics.
     * If not specified, a default "fake-aws-region" will be used.
     *
     * @param region the AWS region
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbClientBuilder region(Region region) {
      this.region = region;
      delegate.region(region);
      return this;
    }

    /**
     * Sets the AWS credentials provider. This method matches
     * {@link DynamoDbClientBuilder#credentialsProvider(AwsCredentialsProvider)}.
     *
     * <p>The credentials are used for authentication with Alternator. Common providers include:
     * <ul>
     *   <li>{@link software.amazon.awssdk.auth.credentials.StaticCredentialsProvider} for
     *       hardcoded credentials</li>
     *   <li>{@link software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider} for
     *       environment-based credentials</li>
     * </ul>
     *
     * @param credentialsProvider the AWS credentials provider
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbClientBuilder credentialsProvider(AwsCredentialsProvider credentialsProvider) {
      delegate.credentialsProvider(credentialsProvider);
      return this;
    }

    /**
     * Sets the endpoint override (seed URI) for Alternator cluster discovery.
     *
     * <p>This is the initial Alternator node that will be contacted to discover all other nodes
     * in the cluster via the {@code /localnodes} API. Once the cluster topology is discovered,
     * requests will be distributed across all discovered nodes using round-robin load balancing.
     *
     * <p>The endpoint should be a complete URI including protocol and port, for example:
     * <ul>
     *   <li>{@code https://localhost:8043} for HTTPS</li>
     *   <li>{@code http://192.168.1.100:8000} for HTTP</li>
     * </ul>
     *
     * <p>This method is required - the build will fail if no endpoint is specified.
     *
     * @param endpointOverride the seed URI for Alternator cluster discovery
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbClientBuilder endpointOverride(URI endpointOverride) {
      this.seedUri = endpointOverride;
      return this;
    }

    /**
     * Sets a custom HTTP client. This method matches
     * {@link DynamoDbClientBuilder#httpClient(SdkHttpClient)}.
     *
     * <p>Use this to configure custom HTTP client settings such as connection pooling,
     * timeouts, or proxy settings. If not specified, the AWS SDK will create a default
     * HTTP client.
     *
     * @param httpClient the HTTP client to use
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbClientBuilder httpClient(SdkHttpClient httpClient) {
      this.httpClientSet = true;
      delegate.httpClient(httpClient);
      return this;
    }

    /**
     * Sets a custom HTTP client builder. This method matches
     * {@link DynamoDbClientBuilder#httpClientBuilder(SdkHttpClient.Builder)}.
     *
     * <p>Use this to configure HTTP client settings using a builder pattern. This is
     * an alternative to {@link #httpClient(SdkHttpClient)} when you want to customize
     * the HTTP client configuration without creating the client instance directly.
     *
     * @param httpClientBuilder the HTTP client builder to use
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbClientBuilder httpClientBuilder(SdkHttpClient.Builder httpClientBuilder) {
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
     * {@link DynamoDbClientBuilder#overrideConfiguration(ClientOverrideConfiguration)}.
     *
     * <p>Use this to configure advanced client settings such as:
     * <ul>
     *   <li>Retry policies</li>
     *   <li>Request timeout settings</li>
     *   <li>Additional request headers</li>
     *   <li>Metric publishers</li>
     * </ul>
     *
     * @param overrideConfiguration the client override configuration
     * @return this builder instance
     */
    @Override
    public AlternatorDynamoDbClientBuilder overrideConfiguration(ClientOverrideConfiguration overrideConfiguration) {
      delegate.overrideConfiguration(overrideConfiguration);
      return this;
    }

    /**
     * Sets client override configuration using a builder consumer. This method matches
     * {@link DynamoDbClientBuilder#overrideConfiguration(Consumer)}.
     *
     * <p>This is a convenience method that allows configuring the override settings
     * inline without creating a separate {@link ClientOverrideConfiguration} instance.
     *
     * <p>Example:
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
    public AlternatorDynamoDbClientBuilder overrideConfiguration(Consumer<ClientOverrideConfiguration.Builder> builderConsumer) {
      delegate.overrideConfiguration(builderConsumer);
      return this;
    }

    /**
     * This method is not supported by AlternatorDynamoDbClient.
     *
     * <p>The endpoint provider is automatically configured to use
     * {@link AlternatorEndpointProvider} for load balancing. Use
     * {@link #endpointOverride(URI)} to specify the seed endpoint instead.
     *
     * @param endpointProvider ignored
     * @return never returns
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    public AlternatorDynamoDbClientBuilder endpointProvider(DynamoDbEndpointProvider endpointProvider) {
      throw new UnsupportedOperationException(
          "AlternatorDynamoDbClient does not support custom endpoint providers. "
          + "Use endpointOverride(URI) to specify the seed endpoint instead.");
    }

    /**
     * This method is not supported by AlternatorDynamoDbClient.
     *
     * <p>Alternator uses its own node discovery mechanism via the {@code /localnodes} API,
     * which is incompatible with AWS endpoint discovery.
     *
     * @param endpointDiscoveryEnabled ignored
     * @return never returns
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    public AlternatorDynamoDbClientBuilder endpointDiscoveryEnabled(boolean endpointDiscoveryEnabled) {
      throw new UnsupportedOperationException(
          "AlternatorDynamoDbClient does not support AWS endpoint discovery. "
          + "Node discovery is handled automatically via the /localnodes API.");
    }

    /**
     * This method is not supported by AlternatorDynamoDbClient.
     *
     * <p>Alternator uses its own node discovery mechanism via the {@code /localnodes} API,
     * which is incompatible with AWS endpoint discovery.
     *
     * @return never returns
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    public AlternatorDynamoDbClientBuilder enableEndpointDiscovery() {
      throw new UnsupportedOperationException(
          "AlternatorDynamoDbClient does not support AWS endpoint discovery. "
          + "Node discovery is handled automatically via the /localnodes API.");
    }

    /**
     * This method is not supported by AlternatorDynamoDbClient.
     *
     * <p>FIPS (Federal Information Processing Standards) mode is an AWS-specific feature
     * that is not applicable to Alternator.
     *
     * @param fipsEnabled ignored
     * @return never returns
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    public AlternatorDynamoDbClientBuilder fipsEnabled(Boolean fipsEnabled) {
      throw new UnsupportedOperationException(
          "AlternatorDynamoDbClient does not support FIPS mode (AWS-specific feature).");
    }

    /**
     * This method is not supported by AlternatorDynamoDbClient.
     *
     * <p>Dual-stack networking (IPv4/IPv6) is an AWS-specific feature that is not
     * applicable to Alternator.
     *
     * @param dualstackEnabled ignored
     * @return never returns
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    public AlternatorDynamoDbClientBuilder dualstackEnabled(Boolean dualstackEnabled) {
      throw new UnsupportedOperationException(
          "AlternatorDynamoDbClient does not support dual-stack networking (AWS-specific feature).");
    }

    /**
     * Builds and returns a DynamoDB client with Alternator load balancing configured.
     *
     * <p>This method performs the following steps:
     * <ol>
     *   <li>Validates that {@link #endpointOverride(URI)} was called (required)</li>
     *   <li>Initializes {@link AlternatorConfig} with default values if not configured</li>
     *   <li>Creates an {@link AlternatorEndpointProvider} with the seed URI and DC/rack settings</li>
     *   <li>Sets a default region ("fake-aws-region") if none was specified</li>
     *   <li>Builds the underlying {@link DynamoDbClient} with all configurations applied</li>
     * </ol>
     *
     * <p>The returned client will automatically:
     * <ul>
     *   <li>Discover all nodes in the Alternator cluster via the {@code /localnodes} API</li>
     *   <li>Distribute requests across discovered nodes using round-robin load balancing</li>
     *   <li>Periodically refresh the node list (every 5 seconds) to handle topology changes</li>
     *   <li>Filter nodes by datacenter/rack if configured via {@link #withAlternatorConfig}</li>
     * </ul>
     *
     * @return a {@link DynamoDbClient} instance configured with Alternator load balancing
     * @throws IllegalStateException if {@link #endpointOverride(URI)} was not called
     */
    @Override
    public DynamoDbClient build() {
      if (seedUri == null) {
        throw new IllegalStateException(
            "endpointOverride must be set when using AlternatorDynamoDbClientBuilder. "
            + "Call endpointOverride(URI) with the seed Alternator node URI.");
      }

      // Initialize alternatorConfig with defaults if null
      if (alternatorConfig == null) {
        alternatorConfig = AlternatorConfig.builder().build();
      }

      // Configure HTTP client to disable certificate checking if requested and no custom client was set
      if (disableCertificateChecks && !httpClientSet) {
        SdkHttpClient httpClient =
            ApacheHttpClient.builder()
                .buildWithDefaults(
                    AttributeMap.builder()
                        .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, true)
                        .build());
        delegate.httpClient(httpClient);
      }

      // Create AlternatorEndpointProvider with the seed URI and DC/rack settings
      AlternatorEndpointProvider alternatorEndpointProvider =
          new AlternatorEndpointProvider(
              seedUri,
              alternatorConfig.getDatacenter(),
              alternatorConfig.getRack());

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