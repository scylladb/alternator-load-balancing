package alternator_loadbalancing_test

import (
	"testing"

	alb "alternator_loadbalancing"

	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/service/dynamodb"
)

var knownNodes = []string{"172.17.0.2"}

func TestCheckIfRackAndDatacenterSetCorrectly_WrongDC(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(9999), alb.WithDatacenter("wrongDC"))
	if err != nil {
		t.Fatalf("Error creating alternator load balancer: %v", err)
	}
	if lb.CheckIfRackAndDatacenterSetCorrectly() == nil {
		t.Fatalf("CheckIfRackAndDatacenterSetCorrectly() should have returned an error")
	}
}

func TestCheckIfRackAndDatacenterSetCorrectly_CorrectDC(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(9999), alb.WithDatacenter("datacenter1"))
	if err != nil {
		t.Fatalf("Error creating alternator load balancer: %v", err)
	}
	if err := lb.CheckIfRackAndDatacenterSetCorrectly(); err != nil {
		t.Fatalf("CheckIfRackAndDatacenterSetCorrectly() unexpectedly returned an error: %v", err)
	}
}

func TestCheckIfRackAndDatacenterSetCorrectly_WrongRack(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(9999), alb.WithDatacenter("datacenter1"), alb.WithRack("wrongRack"))
	if err != nil {
		t.Fatalf("Error creating alternator load balancer: %v", err)
	}
	if lb.CheckIfRackAndDatacenterSetCorrectly() == nil {
		t.Fatalf("CheckIfRackAndDatacenterSetCorrectly() should have returned an error")
	}
}

func TestCheckIfRackAndDatacenterSetCorrectly_CorrectRack(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(9999), alb.WithDatacenter("datacenter1"), alb.WithRack("rack1"))
	if err != nil {
		t.Fatalf("Error creating alternator load balancer: %v", err)
	}
	if err := lb.CheckIfRackAndDatacenterSetCorrectly(); err != nil {
		t.Fatalf("CheckIfRackAndDatacenterSetCorrectly() unexpectedly returned an error: %v", err)
	}
}

func TestCheckIfRackDatacenterFeatureIsSupported(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(9999), alb.WithDatacenter("datacenter1"))
	if err != nil {
		t.Fatalf("Error creating alternator load balancer: %v", err)
	}
	val, err := lb.CheckIfRackDatacenterFeatureIsSupported()
	if err != nil {
		t.Fatalf("CheckIfRackAndDatacenterSetCorrectly() unexpectedly returned an error: %v", err)
	}
	if !val {
		t.Fatalf("CheckIfRackAndDatacenterSetCorrectly() should have returned true")
	}
}

func TestDynamoDBOperations(t *testing.T) {
	const tableName = "test_table"
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(9999))
	if err != nil {
		t.Fatalf("Error creating alternator load balancer: %v", err)
	}
	ddb, err := lb.NewDynamoDB("whatever", "secret")
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
