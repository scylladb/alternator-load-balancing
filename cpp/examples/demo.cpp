#include <aws/core/Aws.h>
#include <aws/core/utils/Outcome.h> 
#include <aws/core/client/DefaultRetryStrategy.h>
#include <aws/dynamodb/DynamoDBClient.h>
#include <aws/dynamodb/model/AttributeDefinition.h>
#include <aws/dynamodb/model/CreateTableRequest.h>
#include <aws/dynamodb/model/KeySchemaElement.h>
#include <aws/dynamodb/model/ScalarAttributeType.h>
#include <aws/dynamodb/model/PutItemRequest.h>
#include <aws/dynamodb/model/PutItemResult.h>
#include <aws/dynamodb/model/ScanRequest.h>
#include <aws/dynamodb/model/ScanResult.h>
#include <iostream>
#include "../AlternatorClient.h"

int main(int argc, char** argv)
{
    if (argc < 2) {
        std::cout << "This demo program creates a table, fills it with example data "
                "and then reads it. Each request is subject to load balancing and will "
                "be sent to different alternator nodes, as long as multiple nodes are available." << std::endl;
        std::cout << "Usage: " << argv[0] << " <table_name> " << std::endl;
        return 1;
    }

    Aws::SDKOptions options;
    Aws::InitAPI(options);

    const Aws::String table(argv[1]);

    Aws::Client::ClientConfiguration config;
    config.verifySSL = false;
    config.retryStrategy = std::shared_ptr<Aws::Client::RetryStrategy>(new Aws::Client::DefaultRetryStrategy(3, 10));
    AlternatorClient alternator("http", "localhost", "8000", config);
    alternator.StartNodeUpdater(std::chrono::seconds(1));

    Aws::DynamoDB::Model::CreateTableRequest create_req;

    // Create a new table
    create_req.SetTableName(table);
    create_req.SetBillingMode(Aws::DynamoDB::Model::BillingMode::PAY_PER_REQUEST);
    create_req.AddAttributeDefinitions(
        Aws::DynamoDB::Model::AttributeDefinition().WithAttributeName("id").WithAttributeType(Aws::DynamoDB::Model::ScalarAttributeType::S)
    );
    create_req.AddKeySchema(
        Aws::DynamoDB::Model::KeySchemaElement().WithAttributeName("id").WithKeyType(Aws::DynamoDB::Model::KeyType::HASH)
    );

    std::cout << "Creating table " << table << std::endl;
    const Aws::DynamoDB::Model::CreateTableOutcome& create_result = alternator.CreateTable(create_req);
    if (create_result.IsSuccess()) {
        std::cout << "Table created:" << std::endl;
        std::cout << create_result.GetResult().GetTableDescription().Jsonize().View().WriteReadable() << std::endl;
    } else {
        std::cout << "Failed to create table: " << create_result.GetError().GetMessage() << std::endl;
        return 1;
    }

    std::cout  << "Filling table " << table << " with data" << std::endl;
    for (int p = 0; p < 5; ++p) {
        Aws::DynamoDB::Model::PutItemRequest put_req;
        put_req.SetTableName(table);

        put_req.AddItem("id", Aws::DynamoDB::Model::AttributeValue().SetS(("item" + std::to_string(p)).c_str()));

        for (int i = 0; i < 3; ++i) {
            const Aws::String attr = ("attr" + std::to_string(i)).c_str();
            const Aws::String val = ("val" + std::to_string(i)).c_str();
            put_req.AddItem(attr, Aws::DynamoDB::Model::AttributeValue().SetS(val));
        }

        const Aws::DynamoDB::Model::PutItemOutcome put_result = alternator.PutItem(put_req);
        if (!put_result.IsSuccess()) {
            std::cout << put_result.GetError().GetMessage() << std::endl;
            return 1;
        }
    }

    std::cout << "Scanning table " << table << std::endl;
    Aws::DynamoDB::Model::ScanRequest scan_req;
    scan_req.SetTableName(table);

    const Aws::DynamoDB::Model::ScanOutcome& scan_result = alternator.Scan(scan_req);
    if (scan_result.IsSuccess()) {
        std::cout << "Scan results:" << std::endl;
        const Aws::Vector<Aws::Map<Aws::String, Aws::DynamoDB::Model::AttributeValue>>& items = scan_result.GetResult().GetItems();
        for (const auto& item : items) {
            std::cout << "Item: " << std::endl;
            for (const auto& attr : item) {
                std::cout << '\t' << attr.first << ":\t" << attr.second.Jsonize().View().WriteCompact() << std::endl;
            }
        }
    } else {
        std::cout << "Failed to scan table: " << scan_result.GetError().GetMessage() << std::endl;
    }

    Aws::ShutdownAPI(options);
}
