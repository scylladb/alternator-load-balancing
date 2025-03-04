package alternator_loadbalancing_v2

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"time"

	aln "alternator_live_nodes"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"

	smithyendpoints "github.com/aws/smithy-go/endpoints"
)

type Config struct {
	Port                  int
	Scheme                string
	Rack                  string
	Datacenter            string
	AWSRegion             string
	NodesListUpdatePeriod time.Duration
	AccessKeyID           string
	SecretAccessKey       string
	HTTPClient            *http.Client
	ClientCertificate     *aln.CertSource
	// Makes it ignore server certificate errors
	IgnoreServerCertificateError bool
}

type Option func(config *Config)

const (
	defaultPort      = 8080
	defaultScheme    = "http"
	defaultAWSRegion = "default-alb-region"
)

func NewConfig() *Config {
	return &Config{
		Port:                  defaultPort,
		Scheme:                defaultScheme,
		AWSRegion:             defaultAWSRegion,
		NodesListUpdatePeriod: 5 * time.Minute,
	}
}

func (c *Config) ToALNConfig() []aln.Option {
	out := []aln.Option{
		aln.WithPort(c.Port),
		aln.WithScheme(c.Scheme),
		aln.WithUpdatePeriod(c.NodesListUpdatePeriod),
	}

	if c.Rack != "" {
		out = append(out, aln.WithRack(c.Rack))
	}

	if c.Datacenter != "" {
		out = append(out, aln.WithDatacenter(c.Datacenter))
	}

	if c.HTTPClient != nil {
		out = append(out, aln.WithHTTPClient(c.HTTPClient))
	}

	return out
}

func WithScheme(scheme string) Option {
	return func(config *Config) {
		config.Scheme = scheme
	}
}

func WithPort(port int) Option {
	return func(config *Config) {
		config.Port = port
	}
}

func WithRack(rack string) Option {
	return func(config *Config) {
		config.Rack = rack
	}
}

func WithDatacenter(dc string) Option {
	return func(config *Config) {
		config.Datacenter = dc
	}
}

func WithAWSRegion(region string) Option {
	return func(config *Config) {
		config.AWSRegion = region
	}
}

func WithNodesListUpdatePeriod(period time.Duration) Option {
	return func(config *Config) {
		config.NodesListUpdatePeriod = period
	}
}

func WithCredentials(accessKeyID string, secretAccessKey string) Option {
	return func(config *Config) {
		config.AccessKeyID = accessKeyID
		config.SecretAccessKey = secretAccessKey
	}
}

func WithHTTPClient(httpClient *http.Client) Option {
	return func(config *Config) {
		config.HTTPClient = httpClient
	}
}

func WithClientCertificateFile(certFile, keyFile string) Option {
	return func(config *Config) {
		config.ClientCertificate = aln.NewFileCertificate(certFile, keyFile)
	}
}

func WithClientCertificate(certificate tls.Certificate) Option {
	return func(config *Config) {
		config.ClientCertificate = aln.NewCertificate(certificate)
	}
}

func WithIgnoreServerCertificateErrors() Option {
	return func(config *Config) {
		config.IgnoreServerCertificateError = true
	}
}

type AlternatorLB struct {
	nodes *aln.AlternatorLiveNodes
	cfg   Config
}

func NewAlternatorLB(initialNodes []string, options ...Option) (*AlternatorLB, error) {
	cfg := NewConfig()
	for _, opt := range options {
		opt(cfg)
	}

	nodes, err := aln.NewAlternatorLiveNodes(initialNodes, cfg.ToALNConfig()...)
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
		cfg.HTTPClient = http.DefaultClient
	}

	if lb.cfg.IgnoreServerCertificateError {
		httpClient, ok := cfg.HTTPClient.(*http.Client)
		if !ok {
			return cfg, errors.New("failed to patch http client for ignore server certificate")
		}
		if httpClient.Transport == nil {
			httpClient.Transport = http.DefaultTransport
		}
		httpTransport, ok := httpClient.Transport.(*http.Transport)
		if !ok {
			return cfg, errors.New("failed to patch http transport for ignore server certificate")
		}
		if httpTransport.TLSClientConfig == nil {
			httpTransport.TLSClientConfig = &tls.Config{}
		}

		httpTransport.TLSClientConfig.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
			return nil
		}

		httpTransport.TLSClientConfig.InsecureSkipVerify = true
	}

	if lb.cfg.ClientCertificate != nil {
		httpClient, ok := cfg.HTTPClient.(*http.Client)
		if !ok {
			return aws.Config{}, fmt.Errorf("failed patch custom HTTP client (%T) for client certificate", cfg.HTTPClient)
		}
		if httpClient.Transport == nil {
			httpClient.Transport = http.DefaultTransport
		}
		transport, ok := httpClient.Transport.(*http.Transport)
		if !ok {
			return aws.Config{}, fmt.Errorf("failed patch custom HTTP transport (%T) for client certificate", httpClient.Transport)
		}
		lb.cfg.ClientCertificate.PatchHTTPTransport(transport)
	}

	if lb.cfg.AccessKeyID != "" && lb.cfg.SecretAccessKey != "" {
		// The third credential below, the session token, is only used for
		// temporary credentials, and is not supported by Alternator anyway.
		cfg.Credentials = credentials.NewStaticCredentialsProvider(lb.cfg.AccessKeyID, lb.cfg.SecretAccessKey, "")
	}

	return cfg, nil
}

// WithCredentials creates clone of AlternatorLB with altered alternator credentials
func (lb *AlternatorLB) WithCredentials(accessKeyID string, secretAccessKey string) *AlternatorLB {
	cfg := lb.cfg
	WithCredentials(accessKeyID, secretAccessKey)(&cfg)
	return &AlternatorLB{
		nodes: lb.nodes,
		cfg:   cfg,
	}
}

// WithAWSRegion creates clone of AlternatorLB with altered AWS region
func (lb *AlternatorLB) WithAWSRegion(region string) *AlternatorLB {
	cfg := lb.cfg
	WithAWSRegion(region)(&cfg)
	return &AlternatorLB{
		nodes: lb.nodes,
		cfg:   cfg,
	}
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
