package alternator_loadbalancing

import (
	"common"
	"fmt"
	"net/http"
	"net/url"

	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/aws/credentials"
	"github.com/aws/aws-sdk-go/aws/session"
	"github.com/aws/aws-sdk-go/service/dynamodb"
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
)

type AlternatorLB struct {
	nodes *common.AlternatorLiveNodes
	cfg   common.Config
}

func NewAlternatorLB(initialNodes []string, options ...Option) (*AlternatorLB, error) {
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

// AWSConfig produces a conf for the AWS SDK that will integrate the alternator loadbalancing with the AWS SDK.
func (lb *AlternatorLB) AWSConfig() (aws.Config, error) {
	cfg := aws.Config{
		Endpoint: aws.String(fmt.Sprintf("%s://%s:%d", lb.cfg.Scheme, "dynamodb.fake.alterntor.cluster.node", lb.cfg.Port)),
		// Region is used in the signature algorithm so prevent request sent
		// to one region to be forward by an attacker to a different region.
		// But Alternator doesn't check it. It can be anything.
		Region: aws.String(lb.cfg.AWSRegion),
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
		return cfg, err
	}

	if lb.cfg.AccessKeyID != "" && lb.cfg.SecretAccessKey != "" {
		// The third credential below, the session token, is only used for
		// temporary credentials, and is not supported by Alternator anyway.
		cfg.Credentials = credentials.NewStaticCredentials(lb.cfg.AccessKeyID, lb.cfg.SecretAccessKey, "")
	}

	cfg.HTTPClient.Transport = lb.wrapHTTPTransport(cfg.HTTPClient.Transport)
	return cfg, nil
}

func (lb *AlternatorLB) NewAWSSession() (*session.Session, error) {
	cfg, err := lb.AWSConfig()
	if err != nil {
		return nil, err
	}

	return session.NewSessionWithOptions(session.Options{
		Config: cfg,
	})
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

func (lb *AlternatorLB) NewDynamoDB() (*dynamodb.DynamoDB, error) {
	sess, err := lb.NewAWSSession()
	if err != nil {
		return nil, err
	}
	return dynamodb.New(sess), nil
}

type roundTripper struct {
	originalTransport http.RoundTripper
	lb                *AlternatorLB
}

func (rt *roundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	node := rt.lb.NextNode()
	req.URL = &node
	return rt.originalTransport.RoundTrip(req)
}

func (lb *AlternatorLB) wrapHTTPTransport(original http.RoundTripper) http.RoundTripper {
	return &roundTripper{
		originalTransport: original,
		lb:                lb,
	}
}
