package com.scylladb.alternator.test;

import com.scylladb.alternator.AlternatorClient;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeEndpointsRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeEndpointsResponse;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.utils.AttributeMap;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;

import java.net.URI;

// For disabling HTTPS certificate checking in HttpsURLConnection
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

// For enabling trace-level logging
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.ConsoleHandler;

public class Demo2 {
    // Set here the authentication credentials needed by the server:
    static AwsCredentialsProvider myCredentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("alternator", "secret_pass"));

    // The following is the "traditional" way to get a DynamoDB connection to
    // a specific endpoint URL, with no client-side load balancing, or any
    // Alternator-specific code.
    static DynamoDbClient getTraditionalClient(URI url) {
        // To support HTTPS connections to a test server *without* checking
        // SSL certificates we need the httpClient() hack. It's of course not
        // needed in a production installation.
        SdkHttpClient http = ApacheHttpClient.builder().buildWithDefaults(
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
    static DynamoDbClient getAlternatorClient(URI url) {
        // To support HTTPS connections to a test server *without* checking
        // SSL certificates we need the httpClient() hack. It's of course not
        // needed in a production installation.
        //SdkHttpClient http = ApacheHttpClient.builder().buildWithDefaults(
        //    AttributeMap.builder()
        //        .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, true)
        //        .build());
        return AlternatorClient.builder(url.getScheme(), url.getHost(), url.getPort())
            .credentialsProvider(myCredentials)
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

        // In our test setup, the Alternator HTTPS server set up with a self-
        // signed certficate, so we need to disable certificate checking.
        // Obviously, this doesn't need to be done in production code.
        disableCertificateChecks();

        //DynamoDbClient ddb = getTraditionalClient(URI.create("https://localhost:8043"));
        DynamoDbClient ddb = getAlternatorClient(URI.create("https://localhost:8043"));

        // run DescribeEndpoints several times
        for (int i=0; i<10; i++) {
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
        //System.setProperty(SDKGlobalConfiguration.DISABLE_CERT_CHECKING_SYSTEM_PROPERTY, "true");

        // And this is used by Java's HttpsURLConnection (which we use only
        // in AlternatorLiveNodes):
        TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {  }
                public void checkServerTrusted(X509Certificate[] certs, String authType) {  }
            }
        };
        try {
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (Exception e) {
            e.printStackTrace();
        }
        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String arg0, SSLSession arg1) {
                        return true;
                    }
                });
    }

}
