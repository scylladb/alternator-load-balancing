package alternator_loadbalancing_v2

import (
	"common"
	"context"
	"fmt"
	"net/http"
	"net/url"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"

	smithyendpoints "github.com/aws/smithy-go/endpoints"
)

type Option = common.Option

var (
	WithScheme                       = common.WithScheme
	WithPort                         = common.WithPort
	WithRack                         = common.WithRack
	WithDatacenter                   = common.WithDatacenter
	WithAWSRegion                    = common.WithAWSRegion
	WithNodesListUpdatePeriod        = common.WithNodesListUpdatePeriod
	WithIdleNodesListUpdatePeriod    = common.WithIdleNodesListUpdatePeriod
	WithCredentials                  = common.WithCredentials
	WithHTTPClient                   = common.WithHTTPClient
	WithLocalNodesReaderHTTPClient   = common.WithLocalNodesReaderHTTPClient
	WithClientCertificateFile        = common.WithClientCertificateFile
	WithClientCertificate            = common.WithClientCertificate
	WithClientCertificateSource      = common.WithClientCertificateSource
	WithIgnoreServerCertificateError = common.WithIgnoreServerCertificateError
	WithOptimizeHeaders              = common.WithOptimizeHeaders
	WithKeyLogWriter                 = common.WithKeyLogWriter
	WithTLSSessionCache              = common.WithTLSSessionCache
	WithMaxIdleHTTPConnections       = common.WithMaxIdleHTTPConnections
)

type AlternatorLB struct {
	nodes *common.AlternatorLiveNodes
	cfg   common.Config
}

func NewAlternatorLB(initialNodes []string, options ...common.Option) (*AlternatorLB, error) {
	cfg := common.NewConfig()
	for _, opt := range options {
		opt(cfg)
	}

	nodes, err := common.NewAlternatorLiveNodes(initialNodes, cfg.ToALNOptions()...)
	if err != nil {
		return nil, err
	}
	return &AlternatorLB{
		nodes: nodes,
		cfg:   *cfg,
	}, nil
}

// AWSConfig produces a conf for the AWS SDK that will integrate the alternator loadbalancing with the AWS SDK.
func (lb *AlternatorLB) AWSConfig() (aws.Config, error) {
	cfg := aws.Config{
		// Region is used in the signature algorithm so prevent request sent
		// to one region to be forward by an attacker to a different region.
		// But Alternator doesn't check it. It can be anything.
		Region:       lb.cfg.AWSRegion,
		BaseEndpoint: aws.String(fmt.Sprintf("%s://%s:%d", lb.cfg.Scheme, "dynamodb.fake.alterntor.cluster.node", lb.cfg.Port)),
	}

	if lb.cfg.HTTPClient != nil {
		cfg.HTTPClient = lb.cfg.HTTPClient
	} else {
		cfg.HTTPClient = &http.Client{
			Transport: common.DefaultHTTPTransport(),
		}
	}

	err := common.PatchHTTPClient(lb.cfg, cfg.HTTPClient)
	if err != nil {
		return aws.Config{}, err
	}

	if lb.cfg.AccessKeyID != "" && lb.cfg.SecretAccessKey != "" {
		// The third credential below, the session token, is only used for
		// temporary credentials, and is not supported by Alternator anyway.
		cfg.Credentials = credentials.NewStaticCredentialsProvider(lb.cfg.AccessKeyID, lb.cfg.SecretAccessKey, "")
	}

	return cfg, nil
}

// WithCredentials creates clone of AlternatorLB with altered alternator credentials
func (lb *AlternatorLB) WithCredentials(accessKeyID, secretAccessKey string) *AlternatorLB {
	cfg := lb.cfg
	common.WithCredentials(accessKeyID, secretAccessKey)(&cfg)
	return &AlternatorLB{
		nodes: lb.nodes,
		cfg:   cfg,
	}
}

// WithAWSRegion creates clone of AlternatorLB with altered AWS region
func (lb *AlternatorLB) WithAWSRegion(region string) *AlternatorLB {
	cfg := lb.cfg
	common.WithAWSRegion(region)(&cfg)
	return &AlternatorLB{
		nodes: lb.nodes,
		cfg:   cfg,
	}
}

func (lb *AlternatorLB) NextNode() url.URL {
	return lb.nodes.NextNode()
}

func (lb *AlternatorLB) UpdateLiveNodes() error {
	return lb.nodes.UpdateLiveNodes()
}

func (lb *AlternatorLB) CheckIfRackAndDatacenterSetCorrectly() error {
	return lb.nodes.CheckIfRackAndDatacenterSetCorrectly()
}

func (lb *AlternatorLB) CheckIfRackDatacenterFeatureIsSupported() (bool, error) {
	return lb.nodes.CheckIfRackDatacenterFeatureIsSupported()
}

func (lb *AlternatorLB) Start() {
	lb.nodes.Start()
}

func (lb *AlternatorLB) Stop() {
	lb.nodes.Stop()
}

func (lb *AlternatorLB) endpointResolverV2() dynamodb.EndpointResolverV2 {
	return &EndpointResolverV2{lb: lb}
}

func (lb *AlternatorLB) NewDynamoDB() (*dynamodb.Client, error) {
	cfg, err := lb.AWSConfig()
	if err != nil {
		return nil, err
	}
	return dynamodb.NewFromConfig(cfg, dynamodb.WithEndpointResolverV2(lb.endpointResolverV2())), nil
}

type EndpointResolverV2 struct {
	lb *AlternatorLB
}

func (r *EndpointResolverV2) ResolveEndpoint(_ context.Context, _ dynamodb.EndpointParameters) (smithyendpoints.Endpoint, error) {
	return smithyendpoints.Endpoint{
		URI: r.lb.NextNode(),
	}, nil
}
