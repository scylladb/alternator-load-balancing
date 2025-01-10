using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using Amazon.Runtime;
using System.Net;

namespace ScyllaDB.Alternator
{
    [TestFixture]
    public class Test
    {
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
        
        private readonly string _user = TestContext.Parameters.Get("User", "none");
        private readonly string _password = TestContext.Parameters.Get("Password", "none");
        private readonly string _endpoint = TestContext.Parameters.Get("Endpoint", "http://127.0.0.1:8080");

        [Test]
        public async Task BasicTableTest([Values("","dc1")] string datacenter, [Values("", "rack1")]string rack)
        {
            DisableCertificateChecks();

            
            var credentials = new BasicAWSCredentials(_user, _password);

            var ddb = GetAlternatorClient(new Uri(_endpoint), credentials, datacenter, rack);

            var rand = new Random();
            string tabName = "table" + rand.Next(1000000);
            
            await ddb.CreateTableAsync(tabName,
                [
                    new("k", KeyType.HASH),
                    new("c", KeyType.RANGE)
                ],
                [
                    new("k", ScalarAttributeType.N),
                    new("c", ScalarAttributeType.N)
                ],
                new ProvisionedThroughput { ReadCapacityUnits = 1, WriteCapacityUnits = 1 });

            // run ListTables several times
            for (int i = 0; i < 10; i++)
            {
                var tables = await ddb.ListTablesAsync();
                Console.WriteLine(tables);
            }

            await ddb.DeleteTableAsync(tabName);
            ddb.Dispose();
        }
        
        // A hack to disable SSL certificate checks. Useful when running with
        // a self-signed certificate. Shouldn't be used in production of course
        static void DisableCertificateChecks()
        {
            // For AWS SDK
            Environment.SetEnvironmentVariable("AWS_DISABLE_CERT_CHECKING", "true");

            // For general HTTPS connections
            ServicePointManager.ServerCertificateValidationCallback = 
                delegate
                {
                    return true;
                };
        }
    }
}
