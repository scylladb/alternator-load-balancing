package alternator_loadbalancing

import (
	"crypto/tls"
	"fmt"
	"github.com/aws/aws-sdk-go/aws/defaults"
	"net/http"
	"net/url"
	"strings"
	"time"

	aln "alternator_live_nodes"

	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/aws/credentials"
	"github.com/aws/aws-sdk-go/aws/request"
	"github.com/aws/aws-sdk-go/aws/session"
	"github.com/aws/aws-sdk-go/service/dynamodb"
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

func (lb *AlternatorLB) NextNode() url.URL {
	return lb.nodes.NextNode()
}

func (lb *AlternatorLB) CheckIfRackAndDatacenterSetCorrectly() error {
	return lb.nodes.CheckIfRackAndDatacenterSetCorrectly()
}

func (lb *AlternatorLB) CheckIfRackDatacenterFeatureIsSupported() (bool, error) {
	return lb.nodes.CheckIfRackDatacenterFeatureIsSupported()
}

func (lb *AlternatorLB) PatchSession(s *session.Session) {
	s.Handlers.Send.PushFront(func(r *request.Request) {
		if strings.Contains(r.HTTPRequest.URL.Host, "dynamodb") {
			node := lb.NextNode()
			r.HTTPRequest.Host = node.Host
			r.HTTPRequest.URL.Host = node.Host
			r.HTTPRequest.URL.Scheme = node.Scheme
		}
	})
	return
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
		cfg.HTTPClient = &http.Client{}
	}

	if lb.cfg.ClientCertificate != nil {
		if cfg.HTTPClient.Transport == nil {
			cfg.HTTPClient.Transport = http.DefaultTransport
		}
		transport, ok := cfg.HTTPClient.Transport.(*http.Transport)
		if !ok {
			lb.cfg.ClientCertificate.PatchHTTPTransport(transport)
		} else {
			return aws.Config{}, fmt.Errorf("failed patch custom HTTP client (%T) for client certificate", cfg.HTTPClient)
		}
	}

	if lb.cfg.AccessKeyID != "" && lb.cfg.SecretAccessKey != "" {
		// The third credential below, the session token, is only used for
		// temporary credentials, and is not supported by Alternator anyway.
		cfg.Credentials = credentials.NewStaticCredentials(lb.cfg.AccessKeyID, lb.cfg.SecretAccessKey, "")
	}

	return cfg, nil
}

func (lb *AlternatorLB) NewAWSSession() (*session.Session, error) {
	handlers := defaults.Handlers()
	handlers.Send.PushFront(func(r *request.Request) {
		if strings.Contains(r.HTTPRequest.URL.Host, "dynamodb") {
			node := lb.nodes.NextNode()
			r.HTTPRequest.Host = node.Host
			r.HTTPRequest.URL.Host = node.Host
			r.HTTPRequest.URL.Scheme = node.Scheme
		}
	})

	cfg, err := lb.AWSConfig()
	if err != nil {
		return nil, err
	}

	return session.NewSessionWithOptions(session.Options{
		Handlers: handlers,
		Config:   cfg,
	})
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

func (lb *AlternatorLB) NewDynamoDB() (*dynamodb.DynamoDB, error) {
	sess, err := lb.NewAWSSession()
	if err != nil {
		return nil, err
	}
	return dynamodb.New(sess), nil
}
