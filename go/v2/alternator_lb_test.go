//go:build integration
// +build integration

package alternator_loadbalancing_v2_test

import (
	"context"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

	alb "alternator_loadbalancing_v2"
)

var (
	knownNodes = []string{"172.41.0.2"}
	httpsPort  = 9999
	httpPort   = 9998
)

func TestCheckIfRackAndDatacenterSetCorrectly_WrongDC(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(httpPort), alb.WithDatacenter("wrongDC"))
	if err != nil {
		t.Errorf("Error creating alternator load balancer: %v", err)
	}
	defer lb.Stop()

	if lb.CheckIfRackAndDatacenterSetCorrectly() == nil {
		t.Errorf("CheckIfRackAndDatacenterSetCorrectly() should have returned an error")
	}
}

func TestCheckIfRackAndDatacenterSetCorrectly_CorrectDC(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(httpPort), alb.WithDatacenter("datacenter1"))
	if err != nil {
		t.Errorf("Error creating alternator load balancer: %v", err)
	}
	defer lb.Stop()

	if err := lb.CheckIfRackAndDatacenterSetCorrectly(); err != nil {
		t.Errorf("CheckIfRackAndDatacenterSetCorrectly() unexpectedly returned an error: %v", err)
	}
}

func TestCheckIfRackAndDatacenterSetCorrectly_WrongRack(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(httpPort), alb.WithDatacenter("wrongDC"), alb.WithRack("wrongRack"))
	if err != nil {
		t.Errorf("Error creating alternator load balancer: %v", err)
	}
	defer lb.Stop()

	if lb.CheckIfRackAndDatacenterSetCorrectly() == nil {
		t.Errorf("CheckIfRackAndDatacenterSetCorrectly() should have returned an error")
	}
}

func TestCheckIfRackAndDatacenterSetCorrectly_CorrectRack(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(httpPort), alb.WithDatacenter("datacenter1"), alb.WithRack("rack1"))
	if err != nil {
		t.Errorf("Error creating alternator load balancer: %v", err)
	}
	defer lb.Stop()

	if err := lb.CheckIfRackAndDatacenterSetCorrectly(); err != nil {
		t.Errorf("CheckIfRackAndDatacenterSetCorrectly() unexpectedly returned an error: %v", err)
	}
}

func TestCheckIfRackDatacenterFeatureIsSupported(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(httpPort), alb.WithDatacenter("datacenter1"))
	if err != nil {
		t.Errorf("Error creating alternator load balancer: %v", err)
	}
	defer lb.Stop()

	val, err := lb.CheckIfRackDatacenterFeatureIsSupported()
	if err != nil {
		t.Errorf("CheckIfRackAndDatacenterSetCorrectly() unexpectedly returned an error: %v", err)
	}
	if !val {
		t.Errorf("CheckIfRackAndDatacenterSetCorrectly() should have returned true")
	}
}

func TestDynamoDBOperations(t *testing.T) {
	t.Run("Plain", func(t *testing.T) {
		testDynamoDBOperations(t, alb.WithPort(httpPort))
	})
	t.Run("SSL", func(t *testing.T) {
		testDynamoDBOperations(t, alb.WithScheme("https"), alb.WithPort(httpsPort), alb.WithIgnoreServerCertificateError(true))
	})
}

func testDynamoDBOperations(t *testing.T, opts ...alb.Option) {
	t.Helper()

	const tableName = "test_table"
	lb, err := alb.NewAlternatorLB(knownNodes, opts...)
	if err != nil {
		t.Errorf("Error creating alternator load balancer: %v", err)
	}
	defer lb.Stop()

	ddb, err := lb.WithCredentials("whatever", "secret").NewDynamoDB()
	if err != nil {
		t.Errorf("Error creating dynamoDB client: %v", err)
	}

	ctx := context.Background()

	_, _ = ddb.DeleteTable(ctx, &dynamodb.DeleteTableInput{
		TableName: aws.String(tableName),
	})

	_, err = ddb.CreateTable(
		ctx,
		&dynamodb.CreateTableInput{
			TableName: aws.String(tableName),
			KeySchema: []types.KeySchemaElement{
				{
					AttributeName: aws.String("ID"),
					KeyType:       "HASH",
				},
			},
			AttributeDefinitions: []types.AttributeDefinition{
				{
					AttributeName: aws.String("ID"),
					AttributeType: "S",
				},
			},
			ProvisionedThroughput: &types.ProvisionedThroughput{
				ReadCapacityUnits:  aws.Int64(1),
				WriteCapacityUnits: aws.Int64(1),
			},
		})
	if err != nil {
		t.Fatalf("Error creating a table: %v", err)
	}

	val, err := attributevalue.MarshalMap(map[string]interface{}{
		"ID":   "123",
		"Name": "value",
	})
	if err != nil {
		t.Fatalf("Error marshalling item: %v", err)
	}

	key, err := attributevalue.Marshal("123")
	if err != nil {
		t.Fatalf("Error marshalling item: %v", err)
	}

	_, err = ddb.PutItem(
		ctx,
		&dynamodb.PutItemInput{
			TableName: aws.String(tableName),
			Item:      val,
		})
	if err != nil {
		t.Fatalf("Error creating table record: %v", err)
	}

	result, err := ddb.GetItem(
		ctx,
		&dynamodb.GetItemInput{
			TableName: aws.String(tableName),
			Key: map[string]types.AttributeValue{
				"ID": key,
			},
		})
	if err != nil {
		t.Fatalf("Error creating a record: %v", err)
	}
	if result.Item == nil {
		t.Errorf("no item found")
	}

	_, err = ddb.DeleteItem(
		ctx,
		&dynamodb.DeleteItemInput{
			TableName: aws.String(tableName),
			Key: map[string]types.AttributeValue{
				"ID": key,
			},
		})
	if err != nil {
		t.Errorf("Error deleting item: %v", err)
	}
}
