package com.scylladb.alternator.test;

import com.amazonaws.SDKGlobalConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.TableCollection;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ListTablesResult;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.scylladb.alternator.AlternatorRequestHandler;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Random;
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

public class Demo1 {

  // The following is the "traditional" way to get a DynamoDB connection to
  // a specific endpoint URL, with no client-side load balancing, or any
  // Alternator-specific code.
  static DynamoDB getTraditionalClient(URI url, AWSCredentialsProvider myCredentials) {
    AmazonDynamoDB client =
        AmazonDynamoDBClientBuilder.standard()
            .withEndpointConfiguration(
                new AwsClientBuilder.EndpointConfiguration(url.toString(), "region-doesnt-matter"))
            .withCredentials(myCredentials)
            .build();
    return new DynamoDB(client);
  }

  // And this is the Alternator-specific way to get a DynamoDB connection
  // which load-balances several Scylla nodes.
  static DynamoDB getAlternatorClient(
      URI uri, AWSCredentialsProvider myCredentials, String datacenter, String rack) {
    AlternatorRequestHandler handler = new AlternatorRequestHandler(uri, datacenter, rack);
    AmazonDynamoDB client =
        AmazonDynamoDBClientBuilder.standard()
            // The endpoint doesn't matter, we will override it anyway in the
            // RequestHandler, but without setting it the library will complain
            // if "region" isn't set in the configuration file.
            .withRegion("region-doesnt-matter")
            .withRequestHandlers(handler)
            .withCredentials(myCredentials)
            .build();
    return new DynamoDB(client);
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
        ArgumentParsers.newFor("Demo1")
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
    // signed certficate, so we need to disable certificate checking.
    // Obviously, this doesn't need to be done in production code.
    disableCertificateChecks();

    AWSCredentialsProvider myCredentials =
        new AWSStaticCredentialsProvider(new BasicAWSCredentials(user, pass));
    DynamoDB ddb;
    if (disableLoadBalancing == null || !disableLoadBalancing) {
      ddb = getAlternatorClient(URI.create(endpoint), myCredentials, datacenter, rack);
    } else {
      ddb = getTraditionalClient(URI.create(endpoint), myCredentials);
    }

    Random rand = new Random();
    String tabName = "table" + rand.nextInt(1000000);
    Table tab =
        ddb.createTable(
            tabName,
            Arrays.asList(
                new KeySchemaElement("k", KeyType.HASH), new KeySchemaElement("c", KeyType.RANGE)),
            Arrays.asList(
                new AttributeDefinition("k", ScalarAttributeType.N),
                new AttributeDefinition("c", ScalarAttributeType.N)),
            new ProvisionedThroughput(0L, 0L));
    // run ListTables several times
    for (int i = 0; i < 10; i++) {
      TableCollection<ListTablesResult> tables = ddb.listTables();
      System.out.println(tables.firstPage());
    }
    tab.delete();
    ddb.shutdown();
  }

  // A hack to disable SSL certificate checks. Useful when running with
  // a self-signed certificate. Shouldn't be used in production of course
  static void disableCertificateChecks() {
    // This is used in AWS SDK v1:
    System.setProperty(SDKGlobalConfiguration.DISABLE_CERT_CHECKING_SYSTEM_PROPERTY, "true");

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
