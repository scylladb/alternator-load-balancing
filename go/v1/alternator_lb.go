package alternator_loadbalancing

import (
	"common"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/aws/credentials"
	"github.com/aws/aws-sdk-go/aws/defaults"
	"github.com/aws/aws-sdk-go/aws/request"
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
	WithCredentials                  = common.WithCredentials
	WithHTTPClient                   = common.WithHTTPClient
	WithLocalNodesReaderHTTPClient   = common.WithLocalNodesReaderHTTPClient
	WithClientCertificateFile        = common.WithClientCertificateFile
	WithClientCertificate            = common.WithClientCertificate
	WithIgnoreServerCertificateError = common.WithIgnoreServerCertificateError
	WithOptimizeHeaders              = common.WithOptimizeHeaders
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

	nodes, err := common.NewAlternatorLiveNodes(initialNodes, cfg.ToALNConfig()...)
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

	if lb.cfg.IgnoreServerCertificateError {
		if cfg.HTTPClient.Transport == nil {
			cfg.HTTPClient.Transport = http.DefaultTransport
		}
		httpTransport, ok := cfg.HTTPClient.Transport.(*http.Transport)
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

	if lb.cfg.OptimizeHeaders {
		allowedHeaders := []string{"Host", "X-Amz-Target", "Content-Length", "Accept-Encoding"}
		if lb.cfg.AccessKeyID != "" {
			allowedHeaders = append(allowedHeaders, "Authorization", "X-Amz-Date")
		}
		cfg.HTTPClient.Transport = common.NewHeaderWhiteListing(cfg.HTTPClient.Transport, allowedHeaders...)
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
