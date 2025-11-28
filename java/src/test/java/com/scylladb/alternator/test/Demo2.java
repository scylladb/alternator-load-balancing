package com.scylladb.alternator.test;

import com.scylladb.alternator.AlternatorConfig;
import com.scylladb.alternator.AlternatorDynamoDbClient;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.cert.X509Certificate;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeEndpointsRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeEndpointsResponse;
import software.amazon.awssdk.utils.AttributeMap;

public class Demo2 {
  // Set here the authentication credentials needed by the server:

  // The following is the "traditional" way to get a DynamoDB connection to
  // a specific endpoint URL, with no client-side load balancing, or any
  // Alternator-specific code.
  static DynamoDbClient getTraditionalClient(URI url, AwsCredentialsProvider myCredentials) {
    // To support HTTPS connections to a test server *without* checking
    // SSL certificates we need the httpClient() hack. It's of course not
    // needed in a production installation.
    SdkHttpClient http =
        ApacheHttpClient.builder()
            .buildWithDefaults(
                AttributeMap.builder()
                    .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, true)
                    .build());
    return DynamoDbClient.builder()
        .endpointOverride(url)
        .credentialsProvider(myCredentials)
        .httpClient(http)
        .build();
  }

  // And this is the Alternator-specific way to get a DynamoDB connection
  // which load-balances several Scylla nodes.
  // Using AlternatorDynamoDbClient.builder() provides a simplified API
  // that automatically integrates the AlternatorEndpointProvider for
  // client-side load balancing.
  static DynamoDbClient getAlternatorClient(
      URI url, AwsCredentialsProvider myCredentials, String datacenter, String rack) {
    // To support HTTPS connections to a test server *without* checking
    // SSL certificates we need the httpClient() hack. It's of course not
    // needed in a production installation.
    SdkHttpClient http =
        ApacheHttpClient.builder()
            .buildWithDefaults(
                AttributeMap.builder()
                    .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, true)
                    .build());

    // Build AlternatorConfig if datacenter or rack is specified
    AlternatorConfig.Builder configBuilder = AlternatorConfig.builder();
    if (datacenter != null && !datacenter.isEmpty()) {
      configBuilder.withDatacenter(datacenter);
    }
    if (rack != null && !rack.isEmpty()) {
      configBuilder.withRack(rack);
    }
    AlternatorConfig config = configBuilder.build();

    return AlternatorDynamoDbClient.builder()
        .endpointOverride(url)
        .credentialsProvider(myCredentials)
        .httpClient(http)
        .region(Region.US_EAST_1) // unused, but if missing can result in error
        .withAlternatorConfig(config)
        .build();
  }

  public static void main(String[] args) {
    // The load balancer library logs the list of live nodes, and hosts
    // it chooses to send requests to, if the FINE logging level is
    // enabled.
    Logger logger = Logger.getLogger("com.scylladb.alternator");
    ConsoleHandler handler = new ConsoleHandler();
    handler.setLevel(Level.FINEST);
    logger.setLevel(Level.FINEST);
    logger.addHandler(handler);
    logger.setUseParentHandlers(false);

    ArgumentParser parser =
        ArgumentParsers.newFor("Demo2")
            .build()
            .defaultHelp(true)
            .description("Simple example of AWS SDK v1 alternator access");

    try {
      parser
          .addArgument("-e", "--endpoint")
          .setDefault(new URI("http://localhost:8043"))
          .help("DynamoDB/Alternator endpoint");
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
    parser.addArgument("-u", "--user").setDefault("none").help("Credentials username");
    parser.addArgument("-p", "--password").setDefault("none").help("Credentials password");
    parser
        .addArgument("--datacenter")
        .type(String.class)
        .setDefault("")
        .help(
            "Target only nodes from particular datacenter. If it is not provided it is going to target datacenter of the endpoint.");
    parser
        .addArgument("--rack")
        .type(String.class)
        .setDefault("")
        .help("Target only nodes from particular rack");
    parser
        .addArgument("--no-lb")
        .type(Boolean.class)
        .setDefault(false)
        .help("Turn off load balancing");

    Namespace ns = null;
    try {
      ns = parser.parseArgs(args);
    } catch (ArgumentParserException e) {
      parser.handleError(e);
      System.exit(1);
    }

    String endpoint = ns.getString("endpoint");
    String user = ns.getString("user");
    String pass = ns.getString("password");
    String datacenter = ns.getString("datacenter");
    String rack = ns.getString("rack");
    Boolean disableLoadBalancing = ns.getBoolean("no-lb");

    // In our test setup, the Alternator HTTPS server set up with a self-
    // signed certificate, so we need to disable certificate checking.
    // Obviously, this doesn't need to be done in production code.
    disableCertificateChecks();
    AwsCredentialsProvider myCredentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(user, pass));
    DynamoDbClient ddb;
    if (disableLoadBalancing == null || !disableLoadBalancing) {
      ddb = getAlternatorClient(URI.create(endpoint), myCredentials, datacenter, rack);
    } else {
      ddb = getTraditionalClient(URI.create(endpoint), myCredentials);
    }

    // run DescribeEndpoints several times
    for (int i = 0; i < 10; i++) {
      DescribeEndpointsRequest request = DescribeEndpointsRequest.builder().build();
      DescribeEndpointsResponse response = ddb.describeEndpoints(request);
      System.out.println(response);
    }
    // FIXME: The AWS SDK leaves behind an IdleConnectionReaper thread,
    // which causes mvn to hang waiting for it to shut down (which it
    // won't). Perhaps if we hold the HttpClient object and close() it,
    // it will get rid of this reaper object.
    // https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/core/client/builder/SdkSyncClientBuilder.html#httpClient-software.amazon.awssdk.http.SdkHttpClient-
    // explains that with httpClient(), "This client must be closed by the
    // user when it is ready to be disposed. The SDK will not close the
    // HTTP client when the service client is closed." Maybe we should
    // use httpClientBuilder() instead, which doesn't have this problem?
  }

  // A hack to disable SSL certificate checks. Useful when running with
  // a self-signed certificate. Shouldn't be used in production of course
  static void disableCertificateChecks() {
    // Unfortunately, the following is no longer supported by AWS SDK v2
    // as it was in v1, so we needed to add the option when building the
    // HTTP client.
    // System.setProperty(SDKGlobalConfiguration.DISABLE_CERT_CHECKING_SYSTEM_PROPERTY, "true");

    // And this is used by Java's HttpsURLConnection (which we use only
    // in AlternatorLiveNodes):
    TrustManager[] trustAllCerts =
        new TrustManager[] {
          new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
              return null;
            }

            public void checkClientTrusted(X509Certificate[] certs, String authType) {}

            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
          }
        };
    try {
      SSLContext sc = SSLContext.getInstance("SSL");
      sc.init(null, trustAllCerts, new java.security.SecureRandom());
      HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
    } catch (Exception e) {
      e.printStackTrace();
    }
    HttpsURLConnection.setDefaultHostnameVerifier(
        new HostnameVerifier() {
          @Override
          public boolean verify(String arg0, SSLSession arg1) {
            return true;
          }
        });
  }
}
