package com.scylladb.alternator.sdkv1;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.SDKGlobalConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.metrics.RequestMetricCollector;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.scylladb.alternator.common.AlternatorConfig;
import com.scylladb.alternator.AlternatorRequestHandler;
import java.net.URI;

/**
 * Builder class for creating DynamoDB clients with Alternator load balancing support.
 *
 * <p>This builder simplifies the construction of a DynamoDB client that automatically distributes
 * requests across all nodes in an Alternator cluster. It provides a fluent API for configuring
 * credentials, datacenter, rack, and the seed endpoint, mimicking the {@link
 * AmazonDynamoDBClientBuilder} API.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // Using individual methods
 * AmazonDynamoDB client = AlternatorDynamoDBClientBuilder.standard()
 *     .withEndpoint("https://localhost:8043")
 *     .withCredentials("username", "password")
 *     .withDatacenter("dc1")
 *     .withRack("rack1")
 *     .build();
 *
 * // Using AlternatorConfig
 * AlternatorConfig config = AlternatorConfig.builder()
 *     .withDatacenter("dc1")
 *     .withRack("rack1")
 *     .build();
 * AmazonDynamoDB client = AlternatorDynamoDBClientBuilder.standard()
 *     .withEndpoint("https://localhost:8043")
 *     .withCredentials("username", "password")
 *     .withAlternatorConfig(config)
 *     .build();
 *
 * // Using EndpointConfiguration (AWS SDK style)
 * AmazonDynamoDB client = AlternatorDynamoDBClientBuilder.standard()
 *     .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(
 *         "https://localhost:8043", "us-east-1"))
 *     .withCredentials(credentialsProvider)
 *     .withDatacenter("dc1")
 *     .build();
 * }</pre>
 *
 * @author dmitry.kropachev
 * @since 1.0.5
 */
public class AlternatorDynamoDBClientBuilder {
  private URI endpoint;
  private AWSCredentialsProvider credentialsProvider;
  private AlternatorConfig alternatorConfig;
  private String region = "fake-aws-region";
  private ClientConfiguration clientConfiguration;
  private RequestHandler2[] requestHandlers;
  private RequestMetricCollector metricsCollector;
  private boolean disableCertificateChecks = false;

  private AlternatorDynamoDBClientBuilder() {}

  /**
   * Creates a new builder instance using the standard builder pattern, similar to {@link
   * AmazonDynamoDBClientBuilder#standard()}.
   *
   * @return a new {@link AlternatorDynamoDBClientBuilder}
   */
  public static AlternatorDynamoDBClientBuilder standard() {
    return new AlternatorDynamoDBClientBuilder();
  }

  /**
   * Sets the AWS credentials provider. This method matches {@link
   * AmazonDynamoDBClientBuilder#withCredentials(AWSCredentialsProvider)}.
   *
   * @param credentialsProvider the credentials provider
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder withCredentials(
      AWSCredentialsProvider credentialsProvider) {
    if (!(credentialsProvider instanceof AWSStaticCredentialsProvider)) {
      throw new IllegalArgumentException(
          "alternator supports only AWSStaticCredentialsProvider credentialsProvider");
    }
    this.credentialsProvider = credentialsProvider;
    return this;
  }

  /**
   * Sets the AWS credentials provider. This is a setter-style method that matches the AWS SDK
   * pattern.
   *
   * @param credentialsProvider the credentials provider
   */
  public void setCredentials(AWSCredentialsProvider credentialsProvider) {
    if (!(credentialsProvider instanceof AWSStaticCredentialsProvider)) {
      throw new IllegalArgumentException(
          "alternator supports only AWSStaticCredentialsProvider credentialsProvider");
    }
    this.credentialsProvider = credentialsProvider;
  }

  /**
   * Sets the AWS credentials using username and password. This is a convenience method that
   * creates a BasicAWSCredentials and wraps it in an AWSStaticCredentialsProvider.
   *
   * @param username the username (access key)
   * @param password the password (secret key)
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder withCredentials(String username, String password) {
    if (username == null || password == null) {
      throw new IllegalArgumentException("Username and password cannot be null");
    }
    this.credentialsProvider = new AWSStaticCredentialsProvider(
        new BasicAWSCredentials(username, password));
    return this;
  }

  /**
   * Sets the Alternator configuration including datacenter and rack settings.
   *
   * @param config the Alternator configuration
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder withAlternatorConfig(AlternatorConfig config) {
    if (config != null) {
      this.alternatorConfig = config;
    }
    return this;
  }

  /**
   * Sets the target datacenter for load balancing. When specified, only nodes from this datacenter
   * will be used for load balancing. If not set, all nodes will be used.
   *
   * @param datacenter the datacenter name
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder withDatacenter(String datacenter) {
    if (this.alternatorConfig == null) {
      this.alternatorConfig = AlternatorConfig.builder().build();
    }
    this.alternatorConfig = AlternatorConfig.builder()
        .withDatacenter(datacenter)
        .withRack(this.alternatorConfig.getRack())
        .build();
    return this;
  }

  /**
   * Sets the target rack for load balancing. When specified along with a datacenter, only nodes
   * from this rack will be used for load balancing.
   *
   * @param rack the rack name
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder withRack(String rack) {
    if (this.alternatorConfig == null) {
      this.alternatorConfig = AlternatorConfig.builder().build();
    }
    this.alternatorConfig = AlternatorConfig.builder()
        .withDatacenter(this.alternatorConfig.getDatacenter())
        .withRack(rack)
        .build();
    return this;
  }

  /**
   * Disables SSL certificate validation for testing purposes.
   *
   * <p><strong>WARNING:</strong> This should only be used for testing with self-signed
   * certificates. Never use this in production as it makes connections vulnerable to
   * man-in-the-middle attacks.
   *
   * <p>This sets the {@code com.amazonaws.sdk.disableCertChecking} system property to disable
   * certificate validation in the AWS SDK v1.
   *
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder withDisableCertificateChecks() {
    this.disableCertificateChecks = true;
    return this;
  }

  /**
   * Sets the Region. It is not used by Alternator, but may appear in logs, traces, metrics.
   *
   * @param region the region name
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder withRegion(String region) {
    if (region == null || region.isEmpty()) {
      throw new IllegalArgumentException("Region cannot be null or empty");
    }
    this.region = region;
    return this;
  }

  /**
   * Sets the Region using the Regions enum. This method matches {@link
   * AmazonDynamoDBClientBuilder#withRegion(Regions)}. The region is not used by Alternator, but may
   * appear in logs, traces, metrics.
   *
   * @param region the region
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder withRegion(Regions region) {
    if (region == null) {
      throw new IllegalArgumentException("Region cannot be null");
    }
    this.region = region.getName();
    return this;
  }

  /**
   * Sets the region. This is a setter-style method that matches the AWS SDK pattern.
   *
   * @param region the region name
   */
  public void setRegion(String region) {
    if (region == null || region.isEmpty()) {
      throw new IllegalArgumentException("Region cannot be null or empty");
    }
    this.region = region;
  }

  /**
   * Sets the region using the Regions enum. This is a setter-style method that matches the AWS SDK
   * pattern.
   *
   * @param region the region
   */
  public void setRegion(Regions region) {
    if (region == null) {
      throw new IllegalArgumentException("Region cannot be null");
    }
    this.region = region.getName();
  }

  /**
   * Sets the client configuration. This method matches {@link AmazonDynamoDBClientBuilder} API and
   * allows configuring HTTP client settings like timeouts, proxy settings, etc.
   *
   * @param clientConfiguration the client configuration
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder withClientConfiguration(
      ClientConfiguration clientConfiguration) {
    this.clientConfiguration = clientConfiguration;
    return this;
  }

  /**
   * Sets the client configuration. This is a setter-style method that matches the AWS SDK pattern.
   *
   * @param clientConfiguration the client configuration
   */
  public void setClientConfiguration(ClientConfiguration clientConfiguration) {
    this.clientConfiguration = clientConfiguration;
  }

  /**
   * Sets the endpoint configuration. This method matches {@link
   * AmazonDynamoDBClientBuilder#withEndpointConfiguration} and provides an alternative way to set
   * the endpoint. The region from the EndpointConfiguration will be used.
   *
   * @param endpointConfiguration the endpoint configuration
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder withEndpointConfiguration(
      AwsClientBuilder.EndpointConfiguration endpointConfiguration) {
    if (endpointConfiguration == null) {
      throw new IllegalArgumentException("EndpointConfiguration cannot be null");
    }
    this.endpoint = URI.create(endpointConfiguration.getServiceEndpoint());
    this.region = endpointConfiguration.getSigningRegion();
    return this;
  }

  /**
   * Sets the endpoint configuration. This is a setter-style method that matches the AWS SDK
   * pattern.
   *
   * @param endpointConfiguration the endpoint configuration
   */
  public void setEndpointConfiguration(
      AwsClientBuilder.EndpointConfiguration endpointConfiguration) {
    if (endpointConfiguration == null) {
      throw new IllegalArgumentException("EndpointConfiguration cannot be null");
    }
    this.endpoint = URI.create(endpointConfiguration.getServiceEndpoint());
    this.region = endpointConfiguration.getSigningRegion();
  }

  /**
   * Sets the endpoint URL as a string. This is a convenience method that creates a URI from the
   * provided endpoint string.
   *
   * @param endpoint the endpoint URL as a string
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder withEndpoint(String endpoint) {
    if (endpoint == null || endpoint.isEmpty()) {
      throw new IllegalArgumentException("Endpoint cannot be null or empty");
    }
    this.endpoint = URI.create(endpoint);
    return this;
  }

  /**
   * Sets additional request handlers. This method matches {@link AmazonDynamoDBClientBuilder} API.
   * These handlers will be added in addition to the AlternatorRequestHandler.
   *
   * @param requestHandlers the request handlers
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder withRequestHandlers(RequestHandler2... requestHandlers) {
    this.requestHandlers = requestHandlers;
    return this;
  }

  /**
   * Sets additional request handlers. This is a setter-style method that matches the AWS SDK
   * pattern.
   *
   * @param requestHandlers the request handlers
   */
  public void setRequestHandlers(RequestHandler2... requestHandlers) {
    this.requestHandlers = requestHandlers;
  }

  /**
   * Sets the metrics collector. This method matches {@link AmazonDynamoDBClientBuilder} API.
   *
   * @param metricsCollector the metrics collector
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder withMetricsCollector(
      RequestMetricCollector metricsCollector) {
    this.metricsCollector = metricsCollector;
    return this;
  }

  /**
   * Sets the metrics collector. This is a setter-style method that matches the AWS SDK pattern.
   *
   * @param metricsCollector the metrics collector
   */
  public void setMetricsCollector(RequestMetricCollector metricsCollector) {
    this.metricsCollector = metricsCollector;
  }

  /**
   * Enables endpoint discovery. This method matches {@link
   * AmazonDynamoDBClientBuilder#enableEndpointDiscovery()}. Note: This setting is not used by
   * Alternator as it has its own node discovery mechanism.
   *
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder enableEndpointDiscovery() {
    throw new UnsupportedOperationException("EndpointDiscovery is not supported by alternator");
  }

  /**
   * Disables endpoint discovery. This method matches the AWS SDK pattern. Note: This setting is not
   * used by Alternator as it has its own node discovery mechanism.
   *
   * @return this builder instance
   */
  public AlternatorDynamoDBClientBuilder disableEndpointDiscovery() {
    throw new UnsupportedOperationException("EndpointDiscovery is not supported by alternator");
  }

  /**
   * Builds and returns a DynamoDB client with load balancing configured.
   *
   * @return a {@link DynamoDB} instance configured with Alternator load balancing
   * @throws IllegalStateException if endpoint or credentials are not set
   */
  public AmazonDynamoDB build() {
    if (endpoint == null) {
      throw new IllegalStateException("Endpoint must be set");
    }

    // Initialize alternatorConfig if null
    if (alternatorConfig == null) {
      alternatorConfig = AlternatorConfig.builder().build();
    }

    AlternatorRequestHandler handler =
        new AlternatorRequestHandler(
            endpoint, alternatorConfig.getDatacenter(), alternatorConfig.getRack());

    // Build the underlying AWS DynamoDB client with all configurations
    AmazonDynamoDBClientBuilder builder =
        AmazonDynamoDBClientBuilder.standard()
            // The region doesn't matter since we override the endpoint in the RequestHandler,
            // but without setting it the library will complain if "region" isn't set
            // in the configuration file.
            .withRegion(region)
            .withCredentials(credentialsProvider);

    // Add the Alternator request handler
    if (requestHandlers != null && requestHandlers.length > 0) {
      // Combine the Alternator handler with any additional handlers
      RequestHandler2[] allHandlers = new RequestHandler2[requestHandlers.length + 1];
      allHandlers[0] = handler;
      System.arraycopy(requestHandlers, 0, allHandlers, 1, requestHandlers.length);
      builder.withRequestHandlers(allHandlers);
    } else {
      builder.withRequestHandlers(handler);
    }

    // Apply optional configurations if provided
    if (clientConfiguration != null) {
      builder.withClientConfiguration(clientConfiguration);
    }

    if (metricsCollector != null) {
      builder.withMetricsCollector(metricsCollector);
    }

    // Disable certificate checking if requested (for testing only)
    if (disableCertificateChecks) {
      System.setProperty(SDKGlobalConfiguration.DISABLE_CERT_CHECKING_SYSTEM_PROPERTY, "true");
    }

    // Note: endpointDiscoveryEnabled is tracked but not used since Alternator
    // has its own node discovery mechanism via AlternatorLiveNodes

    return builder.build();
  }
}
