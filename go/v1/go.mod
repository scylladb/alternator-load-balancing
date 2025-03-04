module alternator_loadbalancing

go 1.22.12

require (
	common v0.0.0-00010101000000-000000000000
	github.com/aws/aws-sdk-go v1.55.6
)

require github.com/jmespath/go-jmespath v0.4.0 // indirect

replace common => ./../common
