module alternator_loadbalancing

go 1.22.12

require (
	alternator_live_nodes v0.0.0-00010101000000-000000000000
	github.com/aws/aws-sdk-go v1.55.6
	github.com/jmespath/go-jmespath v0.4.0 // indirect
)

replace alternator_live_nodes => ./../alternator_live_nodes
