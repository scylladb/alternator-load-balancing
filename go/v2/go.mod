module alternator_loadbalancing_v2

go 1.22.12

require (
	common v0.0.0-00010101000000-000000000000
	github.com/aws/aws-sdk-go-v2 v1.36.3
	github.com/aws/aws-sdk-go-v2/credentials v1.17.61
	github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue v1.18.6
	github.com/aws/aws-sdk-go-v2/service/dynamodb v1.41.0
	github.com/aws/smithy-go v1.22.3
)

require (
	github.com/aws/aws-sdk-go-v2/internal/configsources v1.3.34 // indirect
	github.com/aws/aws-sdk-go-v2/internal/endpoints/v2 v2.6.34 // indirect
	github.com/aws/aws-sdk-go-v2/service/dynamodbstreams v1.25.0 // indirect
	github.com/aws/aws-sdk-go-v2/service/internal/accept-encoding v1.12.3 // indirect
	github.com/aws/aws-sdk-go-v2/service/internal/endpoint-discovery v1.10.15 // indirect
)

replace common => ./../common
