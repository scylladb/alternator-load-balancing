//go:build integration
// +build integration

package alternator_loadbalancing_test

import (
	"testing"

	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/service/dynamodb"

	alb "alternator_loadbalancing"
)

var (
	knownNodes = []string{"172.41.0.2"}
	httpsPort  = 9999
	httpPort   = 9998
)

func TestCheckIfRackAndDatacenterSetCorrectly_WrongDC(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(httpPort), alb.WithDatacenter("wrongDC"))
	if err != nil {
		t.Fatalf("Error creating alternator load balancer: %v", err)
	}
	defer lb.Stop()

	if lb.CheckIfRackAndDatacenterSetCorrectly() == nil {
		t.Fatalf("CheckIfRackAndDatacenterSetCorrectly() should have returned an error")
	}
}

func TestCheckIfRackAndDatacenterSetCorrectly_CorrectDC(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(httpPort), alb.WithDatacenter("datacenter1"))
	if err != nil {
		t.Fatalf("Error creating alternator load balancer: %v", err)
	}
	defer lb.Stop()

	if err := lb.CheckIfRackAndDatacenterSetCorrectly(); err != nil {
		t.Fatalf("CheckIfRackAndDatacenterSetCorrectly() unexpectedly returned an error: %v", err)
	}
}

func TestCheckIfRackAndDatacenterSetCorrectly_WrongRack(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(httpPort), alb.WithDatacenter("datacenter1"), alb.WithRack("wrongRack"))
	if err != nil {
		t.Fatalf("Error creating alternator load balancer: %v", err)
	}
	defer lb.Stop()

	if lb.CheckIfRackAndDatacenterSetCorrectly() == nil {
		t.Fatalf("CheckIfRackAndDatacenterSetCorrectly() should have returned an error")
	}
}

func TestCheckIfRackAndDatacenterSetCorrectly_CorrectRack(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(httpPort), alb.WithDatacenter("datacenter1"), alb.WithRack("rack1"))
	if err != nil {
		t.Fatalf("Error creating alternator load balancer: %v", err)
	}
	defer lb.Stop()

	if err := lb.CheckIfRackAndDatacenterSetCorrectly(); err != nil {
		t.Fatalf("CheckIfRackAndDatacenterSetCorrectly() unexpectedly returned an error: %v", err)
	}
}

func TestCheckIfRackDatacenterFeatureIsSupported(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(httpPort), alb.WithDatacenter("datacenter1"))
	if err != nil {
		t.Fatalf("Error creating alternator load balancer: %v", err)
	}
	defer lb.Stop()

	val, err := lb.CheckIfRackDatacenterFeatureIsSupported()
	if err != nil {
		t.Fatalf("CheckIfRackAndDatacenterSetCorrectly() unexpectedly returned an error: %v", err)
	}
	if !val {
		t.Fatalf("CheckIfRackAndDatacenterSetCorrectly() should have returned true")
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
		t.Fatalf("Error creating alternator load balancer: %v", err)
	}
	defer lb.Stop()

	ddb, err := lb.WithCredentials("whatever", "secret").NewDynamoDB()
	if err != nil {
		t.Fatalf("Error creating dynamoDB client: %v", err)
	}

	_, _ = ddb.DeleteTable(&dynamodb.DeleteTableInput{
		TableName: aws.String(tableName),
	})

	_, err = ddb.CreateTable(
		&dynamodb.CreateTableInput{
			TableName: aws.String(tableName),
			KeySchema: []*dynamodb.KeySchemaElement{
				{
					AttributeName: aws.String("ID"),
					KeyType:       aws.String("HASH"),
				},
			},
			AttributeDefinitions: []*dynamodb.AttributeDefinition{
				{
					AttributeName: aws.String("ID"),
					AttributeType: aws.String("S"),
				},
			},
			ProvisionedThroughput: &dynamodb.ProvisionedThroughput{
				ReadCapacityUnits:  aws.Int64(1),
				WriteCapacityUnits: aws.Int64(1),
			},
		})
	if err != nil {
		t.Fatalf("Error creating a table: %v", err)
	}

	_, err = ddb.PutItem(
		&dynamodb.PutItemInput{
			TableName: aws.String(tableName),
			Item: map[string]*dynamodb.AttributeValue{
				"ID":   {S: aws.String("123")},
				"Data": {S: aws.String("data")},
			},
		})
	if err != nil {
		t.Fatalf("Error creating table record: %v", err)
	}

	result, err := ddb.GetItem(
		&dynamodb.GetItemInput{
			TableName: aws.String(tableName),
			Key: map[string]*dynamodb.AttributeValue{
				"ID": {S: aws.String("123")},
			},
		})
	if err != nil {
		t.Fatalf("Error creating alternator load balancer: %v", err)
	}
	if result.Item == nil {
		t.Fatalf("no item found for table %s", tableName)
	}

	_, err = ddb.DeleteItem(
		&dynamodb.DeleteItemInput{
			TableName: aws.String(tableName),
			Key: map[string]*dynamodb.AttributeValue{
				"ID": {S: aws.String("123")},
			},
		})
	if err != nil {
		t.Fatalf("Error deleting item: %v", err)
	}
}
