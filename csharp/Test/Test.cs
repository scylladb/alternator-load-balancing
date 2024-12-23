using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.DocumentModel;
using Amazon.DynamoDBv2.Model;
using Amazon.Runtime;
using CommandLine;
using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
using System.Linq;
using ScyllaDB.Alternator;
using NUnit.Framework;

namespace ScyllaDB.Alternator
{
    [TestFixture]
    public class Test
    {
        private static TestContext TestContextInstance { get; set; }
        
        [Test]
        public void Test1()
        {
            throw new NotImplementedException();
        }
        
        // And this is the Alternator-specific way to get a DynamoDB connection
        // which load-balances several Scylla nodes.
        private static AmazonDynamoDBClient GetAlternatorClient(Uri uri, AWSCredentials credentials, string datacenter, string rack)
        {
            var handler = new EndpointProvider(uri, datacenter, rack);
            var config = new AmazonDynamoDBConfig
            {
                RegionEndpoint = Amazon.RegionEndpoint.USEast1, // Region doesn't matter
                EndpointProvider = handler
            };
            
            return new AmazonDynamoDBClient(credentials, config);
        }
        
        private string User = TestContext.Parameters.Get("User", "none");
        private string Password = TestContext.Parameters.Get("Password", "none");
        private string Endpoint = TestContext.Parameters.Get("Endpoint", "http://127.0.0.1:8080");

        [Test]
        public async Task runTest([Values("","dc1")] string datacenter, [Values("", "rack1")]string rack)
        {
            DisableCertificateChecks();

            
            var credentials = new BasicAWSCredentials(User, Password);
            AmazonDynamoDBClient ddb;
            
            ddb = GetAlternatorClient(new Uri(Endpoint), credentials, datacenter, rack);

            var rand = new Random();
            string tabName = "table" + rand.Next(1000000);
            
            var table = await ddb.CreateTableAsync(tabName,
                new List<KeySchemaElement>
                {
                    new KeySchemaElement("k", KeyType.HASH),
                    new KeySchemaElement("c", KeyType.RANGE)
                },
                new List<AttributeDefinition>
                {
                    new AttributeDefinition("k", ScalarAttributeType.N),
                    new AttributeDefinition("c", ScalarAttributeType.N)
                },
                new ProvisionedThroughput { ReadCapacityUnits = 0, WriteCapacityUnits = 0 });

            // run ListTables several times
            for (int i = 0; i < 10; i++)
            {
                var tables = await ddb.ListTablesAsync();
                Console.WriteLine(tables);
            }

            await ddb.DeleteTableAsync(tabName);
            ddb.Dispose();
        }

        static void HandleParseError(IEnumerable<Error> errs)
        {
            Environment.Exit(1);
        }

        // A hack to disable SSL certificate checks. Useful when running with
        // a self-signed certificate. Shouldn't be used in production of course
        static void DisableCertificateChecks()
        {
            // For AWS SDK
            System.Environment.SetEnvironmentVariable("AWS_DISABLE_CERT_CHECKING", "true");

            // For general HTTPS connections
            ServicePointManager.ServerCertificateValidationCallback = 
                delegate (object sender, X509Certificate certificate, X509Chain chain, SslPolicyErrors sslPolicyErrors)
                {
                    return true;
                };
        }
    }
}
