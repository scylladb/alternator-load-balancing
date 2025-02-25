package alternator_loadbalancing_v2

import (
	"context"
	"net/url"
	"strings"

	aln "alternator_live_nodes"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"

	smithyendpoints "github.com/aws/smithy-go/endpoints"
	"github.com/aws/smithy-go/middleware"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

type Config = aln.Config
type Option = aln.Option

var (
	WithScheme       = aln.WithScheme
	WithPort         = aln.WithPort
	WithRack         = aln.WithRack
	WithDatacenter   = aln.WithDatacenter
	WithHTTPClient   = aln.WithHTTPClient
	WithUpdatePeriod = aln.WithUpdatePeriod
)

type AlternatorLB struct {
	nodes *aln.AlternatorLiveNodes
}

func NewAlternatorLB(initialNodes []string, options ...Option) (*AlternatorLB, error) {
	aln, err := aln.NewAlternatorLiveNodes(initialNodes, options...)
	if err != nil {
		return nil, err
	}
	return &AlternatorLB{
		nodes: aln,
	}, nil
}

// Config produces a conf for the AWS SDK that will integrate the alternator loadbalancing with the AWS SDK.
func (lb *AlternatorLB) Config(key string, secret string) aws.Config {
	return aws.Config{
		// Region is used in the signature algorithm so prevent request sent
		// to one region to be forward by an attacker to a different region.
		// But Alternator doesn't check it. It can be anything.
		Region: "whatever",
		// The third credential below, the session token, is only used for
		// temporary credentials, and is not supported by Alternator anyway.
		Credentials: credentials.NewStaticCredentialsProvider(key, secret, ""),

		//APIOptions: []func(*middleware.Stack) error{
		//	func(m *middleware.Stack) error {
		//		return m.Finalize.Add(lb.loadBalancerMiddleware(), middleware.Before)
		//	},
		//},
	}
}

func (lb *AlternatorLB) loadBalancerMiddleware() middleware.FinalizeMiddleware {
	middlewareFunc := func(ctx context.Context, in middleware.FinalizeInput, next middleware.FinalizeHandler) (middleware.FinalizeOutput, middleware.Metadata, error) {
		switch v := in.Request.(type) {
		case *smithyhttp.Request:
			if v.URL != nil && strings.Contains(v.URL.Host, "dynamodb") {
				*v.URL = lb.NextNode()
			}
		}

		return next.HandleFinalize(ctx, in)
	}

	return middleware.FinalizeMiddlewareFunc("alternator-lb", middlewareFunc)
}

func (lb *AlternatorLB) NextNode() url.URL {
	return lb.nodes.NextNode()
}

func (lb *AlternatorLB) CheckIfRackAndDatacenterSetCorrectly() error {
	return lb.nodes.CheckIfRackAndDatacenterSetCorrectly()
}

func (lb *AlternatorLB) CheckIfRackDatacenterFeatureIsSupported() (bool, error) {
	return lb.nodes.CheckIfRackDatacenterFeatureIsSupported()
}

func (lb *AlternatorLB) EndpointResolverV2() dynamodb.EndpointResolverV2 {
	return &EndpointResolverV2{lb: lb}
}

func (lb *AlternatorLB) WithEndpointResolverV2() func(*dynamodb.Options) {
	return dynamodb.WithEndpointResolverV2(lb.EndpointResolverV2())
}

func (lb *AlternatorLB) NewDynamoDB(key string, secret string) *dynamodb.Client {
	return dynamodb.NewFromConfig(lb.Config(key, secret), lb.WithEndpointResolverV2())
}

type EndpointResolverV2 struct {
	lb *AlternatorLB
}

func (r *EndpointResolverV2) ResolveEndpoint(_ context.Context, _ dynamodb.EndpointParameters) (smithyendpoints.Endpoint, error) {
	return smithyendpoints.Endpoint{
		URI: r.lb.NextNode(),
	}, nil
}
