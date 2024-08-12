// To run this example, just run:
//       go test example_test.go

package alternatorlb_test

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	alternatorlb "github.com/scylladb/alternator-load-balancing/go/v2"
	"net/http"
	"time"
)

var customPEMCertificate = []byte(nil)

func ExampleAlternatorNodes() {
	ctx := context.Background()

	// Uncomment to use Amazon DynamoDB configured in ~/.aws/
	//    cfg, _ := config.LoadDefaultConfig(ctx)
	//    db := dynamodb.NewFromConfig(cfg)

	// Use the local Alternator with our silly testing alternator/secret_pass
	// authentication - and the new load balancing code.
	alternatorNodes := alternatorlb.NewAlternatorNodes("http", 8000, "127.0.0.1")

	// To add custom CA certificate on top of system CA certificates:
	if customPEMCertificate != nil {
		systemPool, err := x509.SystemCertPool()
		if err != nil {
			panic(err)
		}

		if !systemPool.AppendCertsFromPEM(customPEMCertificate) {
			panic("failed to append custom certificate")
		}

		alternatorNodes.SetHTTPClient(&http.Client{
			Transport: &http.Transport{
				TLSClientConfig: &tls.Config{
					RootCAs:            systemPool,
				},
			},
		})
	}

	alternatorNodes.Start(ctx, 1*time.Second)
	defer alternatorNodes.Stop()

	cfg := alternatorNodes.Config("dog.scylladb.com", "alternator", "secret_pass")
	db := dynamodb.NewFromConfig(cfg)

	for i := 1; i < 20; i++ {
		time.Sleep(300 * time.Millisecond)
		// Do the simplest possible request - DescribeEndpoints
		result, err := db.DescribeEndpoints(ctx, &dynamodb.DescribeEndpointsInput{})
		if err != nil {
			fmt.Println(err.Error())
		} else {
			fmt.Println("response:", *result.Endpoints[0].Address)
		}
	}
}
