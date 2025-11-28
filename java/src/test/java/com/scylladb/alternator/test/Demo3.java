package com.scylladb.alternator.test;

import static java.util.concurrent.Executors.newFixedThreadPool;

import com.scylladb.alternator.AlternatorConfig;
import com.scylladb.alternator.AlternatorDynamoDbAsyncClient;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
// For enabling trace-level logging
import java.util.logging.Logger;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientAsyncConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.core.internal.http.loader.DefaultSdkAsyncHttpClientBuilder;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeEndpointsRequest;
import software.amazon.awssdk.utils.AttributeMap;

public class Demo3 {
  public static void main(String[] args) throws MalformedURLException {
    ArgumentParser parser =
        ArgumentParsers.newFor("Demo3")
            .build()
            .defaultHelp(true)
            .description("Simple example of AWS SDK v2 async alternator access");

    parser
        .addArgument("-e", "--endpoint")
        .setDefault(new URL("http://localhost:8043"))
        .help("DynamoDB/Alternator endpoint");

    parser.addArgument("-u", "--user").setDefault("none").help("Credentials username");
    parser.addArgument("-p", "--password").setDefault("none").help("Credentials password");
    parser.addArgument("-r", "--region").setDefault("us-east-1").help("AWS region");
    parser
        .addArgument("--threads")
        .type(Integer.class)
        .setDefault(Runtime.getRuntime().availableProcessors() * 2)
        .help("Max worker threads");
    parser
        .addArgument("--trust-ssl")
        .type(Boolean.class)
        .setDefault(false)
        .help("Trust all certificates");
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
    int threads = ns.getInt("threads");
    Region region = Region.of(ns.getString("region"));
    Boolean trustSSL = ns.getBoolean("trust-ssl");
    String datacenter = ns.getString("datacenter");
    String rack = ns.getString("rack");

    // The load balancer library logs the list of live nodes, and hosts
    // it chooses to send requests to, if the FINE logging level is
    // enabled.
    Logger logger = Logger.getLogger("com.scylladb.alternator");
    ConsoleHandler handler = new ConsoleHandler();
    handler.setLevel(Level.FINEST);
    logger.setLevel(Level.FINEST);
    logger.addHandler(handler);
    logger.setUseParentHandlers(false);

    ExecutorService executor = newFixedThreadPool(threads);
    ClientAsyncConfiguration cas =
        ClientAsyncConfiguration.builder()
            .advancedOption(SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, executor)
            .build();

    // Build AlternatorConfig if datacenter or rack is specified
    AlternatorConfig.Builder configBuilder = AlternatorConfig.builder();
    if (datacenter != null && !datacenter.isEmpty()) {
      configBuilder.withDatacenter(datacenter);
    }
    if (rack != null && !rack.isEmpty()) {
      configBuilder.withRack(rack);
    }
    AlternatorConfig config = configBuilder.build();

    // Build the async client using AlternatorDynamoDbAsyncClient
    AlternatorDynamoDbAsyncClient.AlternatorDynamoDbAsyncClientBuilder b =
        AlternatorDynamoDbAsyncClient.builder()
            .region(region)
            .asyncConfiguration(cas)
            .withAlternatorConfig(config);

    if (endpoint != null) {
      URI uri = URI.create(endpoint);
      b.endpointOverride(uri);

      if (trustSSL != null && trustSSL.booleanValue()) {
        // In our test setup, the Alternator HTTPS server set up with a
        // self-signed certficate, so we need to disable certificate
        // checking. Obviously, this doesn't need to be done in
        // production code.
        SdkAsyncHttpClient http =
            new DefaultSdkAsyncHttpClientBuilder()
                .buildWithDefaults(
                    AttributeMap.builder()
                        .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, true)
                        .build());
        b.httpClient(http);
      }
    }

    if (user != null) {
      AwsCredentialsProvider cp =
          StaticCredentialsProvider.create(AwsBasicCredentials.create(user, pass));
      b.credentialsProvider(cp);
    }

    DynamoDbAsyncClient dynamoDBClient = b.build();

    // run DescribeEndpoints several times

    List<CompletableFuture<Void>> responses = new ArrayList<>();

    for (int i = 0; i < 10; i++) {
      responses.add(
          dynamoDBClient
              .describeEndpoints(DescribeEndpointsRequest.builder().build())
              .thenAccept(response -> System.out.println(response)));
    }

    CompletableFuture.allOf(responses.toArray(new CompletableFuture[responses.size()])).join();

    System.out.println("Done");
    System.exit(0);
  }
}
