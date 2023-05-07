// To run this example, just run:
//       go run example.go

package main

import (
	"context"
	"fmt"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
)

func main() {
	ctx := context.Background()

	// Uncomment to use Amazon DynamoDB configured in ~/.aws/
	//    cfg, _ := config.LoadDefaultConfig(ctx)
	//    db := dynamodb.NewFromConfig(cfg)

	// Use the local Alternator with our silly testing alternator/secret_pass
	// authentication - and the new load balancing code.
	alternatorNodes := NewAlternatorNodes("http", 8000, "127.0.0.1")
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
