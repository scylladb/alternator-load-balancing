package alternator_loadbalancing_v2_test

import (
	"context"
	"testing"

	alb "alternator_loadbalancing_v2"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

var knownNodes = []string{"172.17.0.2"}

func TestCheckIfRackAndDatacenterSetCorrectly_WrongDC(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(9999), alb.WithDatacenter("wrongDC"))
	if err != nil {
		t.Errorf("Error creating alternator load balancer: %v", err)
	}
	if lb.CheckIfRackAndDatacenterSetCorrectly() == nil {
		t.Errorf("CheckIfRackAndDatacenterSetCorrectly() should have returned an error")
	}
}

func TestCheckIfRackAndDatacenterSetCorrectly_CorrectDC(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(9999), alb.WithDatacenter("datacenter1"))
	if err != nil {
		t.Errorf("Error creating alternator load balancer: %v", err)
	}
	if err := lb.CheckIfRackAndDatacenterSetCorrectly(); err != nil {
		t.Errorf("CheckIfRackAndDatacenterSetCorrectly() unexpectedly returned an error: %v", err)
	}
}

func TestCheckIfRackAndDatacenterSetCorrectly_WrongRack(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(9999), alb.WithDatacenter("wrongDC"), alb.WithRack("wrongRack"))
	if err != nil {
		t.Errorf("Error creating alternator load balancer: %v", err)
	}
	if lb.CheckIfRackAndDatacenterSetCorrectly() == nil {
		t.Errorf("CheckIfRackAndDatacenterSetCorrectly() should have returned an error")
	}
}

func TestCheckIfRackAndDatacenterSetCorrectly_CorrectRack(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(9999), alb.WithDatacenter("datacenter1"), alb.WithRack("rack1"))
	if err != nil {
		t.Errorf("Error creating alternator load balancer: %v", err)
	}
	if err := lb.CheckIfRackAndDatacenterSetCorrectly(); err != nil {
		t.Errorf("CheckIfRackAndDatacenterSetCorrectly() unexpectedly returned an error: %v", err)
	}
}

func TestCheckIfRackDatacenterFeatureIsSupported(t *testing.T) {
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(9999), alb.WithDatacenter("datacenter1"))
	if err != nil {
		t.Errorf("Error creating alternator load balancer: %v", err)
	}
	val, err := lb.CheckIfRackDatacenterFeatureIsSupported()
	if err != nil {
		t.Errorf("CheckIfRackAndDatacenterSetCorrectly() unexpectedly returned an error: %v", err)
	}
	if !val {
		t.Errorf("CheckIfRackAndDatacenterSetCorrectly() should have returned true")
	}
}

func TestDynamoDBOperations(t *testing.T) {
	const tableName = "test_table"
	lb, err := alb.NewAlternatorLB(knownNodes, alb.WithPort(9999))
	if err != nil {
		t.Errorf("Error creating alternator load balancer: %v", err)
	}
	ddb := lb.WithCredentials("whatever", "secret").NewDynamoDB()
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
