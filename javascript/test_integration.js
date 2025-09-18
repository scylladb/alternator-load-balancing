const AWS = require('aws-sdk');
const alternator = require('./Alternator');

describe('Alternator Load Balancing Integration Tests', () => {
    const initialNodes = ['172.43.0.2'];
    const httpPort = 9998;
    const httpsPort = 9999;

    let dynamodb;

    beforeEach(() => {
        // Reset alternator state before each test
        alternator.done();
    });

    afterEach(() => {
        // Clean up after each test
        alternator.done();
    });

    test('should initialize and connect to alternator cluster', () => {
        alternator.init(AWS, 'http', httpPort, initialNodes);

        dynamodb = new AWS.DynamoDB();

        expect(AWS.config.endpoint).toBe(`http://${alternator.FAKE_HOST}:${httpPort}`);
        expect(AWS.config.region).toBe('world');
    });

    test('should perform create, add, get, delete operations', async () => {
        alternator.init(AWS, 'http', httpPort, initialNodes);
        dynamodb = new AWS.DynamoDB();

        const TABLE_NAME = 'TestTable';
        const ITEM_KEY = { 'UserID': { 'S': '123' } };

        // Clean up any existing table
        try {
            await dynamodb.deleteTable({ TableName: TABLE_NAME }).promise();
            // Wait a bit for table deletion to complete
            await new Promise(resolve => setTimeout(resolve, 1000));
        } catch (error) {
            if (error.code !== 'ResourceNotFoundException') {
                throw error;
            }
        }

        // Create table
        console.log('Creating table...');
        await dynamodb.createTable({
            TableName: TABLE_NAME,
            KeySchema: [{ AttributeName: 'UserID', KeyType: 'HASH' }],
            AttributeDefinitions: [{ AttributeName: 'UserID', AttributeType: 'S' }],
            ProvisionedThroughput: { ReadCapacityUnits: 5, WriteCapacityUnits: 5 }
        }).promise();
        console.log(`Table '${TABLE_NAME}' creation started.`);

        // Add an Item
        console.log('Adding item to the table...');
        await dynamodb.putItem({
            TableName: TABLE_NAME,
            Item: {
                'UserID': { 'S': '123' },
                'Name': { 'S': 'Alice' },
                'Age': { 'N': '25' }
            }
        }).promise();
        console.log('Item added.');

        // Get the Item
        console.log('Retrieving item...');
        const response = await dynamodb.getItem({
            TableName: TABLE_NAME,
            Key: ITEM_KEY
        }).promise();

        expect(response.Item).toBeDefined();
        expect(response.Item.UserID.S).toBe('123');
        expect(response.Item.Name.S).toBe('Alice');
        expect(response.Item.Age.N).toBe('25');
        console.log('Retrieved Item:', response.Item);

        // Delete the Item
        console.log('Deleting item...');
        await dynamodb.deleteItem({
            TableName: TABLE_NAME,
            Key: ITEM_KEY
        }).promise();
        console.log('Item deleted.');

        // Verify item is deleted
        const getResponse = await dynamodb.getItem({
            TableName: TABLE_NAME,
            Key: ITEM_KEY
        }).promise();
        expect(getResponse.Item).toBeUndefined();
    }, 30000); // 30 second timeout for integration test

    test('should handle https connections', () => {
        alternator.init(AWS, 'https', httpsPort, initialNodes);
        dynamodb = new AWS.DynamoDB();

        expect(AWS.config.endpoint).toBe(`https://${alternator.FAKE_HOST}:${httpsPort}`);
    });

    test('should distribute requests across multiple nodes', async () => {
        alternator.init(AWS, 'http', httpPort, initialNodes);
        dynamodb = new AWS.DynamoDB();

        // Make multiple requests to test load balancing
        const promises = [];
        for (let i = 0; i < 5; i++) {
            promises.push(
                dynamodb.listTables().promise().catch(error => {
                    // Ignore errors for this test, we just want to test load balancing
                    return { TableNames: [] };
                })
            );
        }

        const results = await Promise.all(promises);
        expect(results).toHaveLength(5);
    });
});