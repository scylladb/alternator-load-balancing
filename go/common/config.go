package common

import (
	"crypto/tls"
	"net/http"
	"time"
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
	ALNHTTPClient         *http.Client
	ClientCertificate     *CertSource
	// Makes it ignore server certificate errors
	IgnoreServerCertificateError bool
	// OptimizeHeaders - when true removes unnecessary http headers reducing network footprint
	OptimizeHeaders bool
	// Update node list when no requests are running
	IdleNodesListUpdatePeriod time.Duration
}

type Option func(config *Config)

const (
	defaultPort      = 8080
	defaultScheme    = "http"
	defaultAWSRegion = "default-alb-region"
)

func NewConfig() *Config {
	return &Config{
		Port:                      defaultPort,
		Scheme:                    defaultScheme,
		AWSRegion:                 defaultAWSRegion,
		NodesListUpdatePeriod:     5 * time.Minute,
		IdleNodesListUpdatePeriod: 2 * time.Hour,
	}
}

func (c *Config) ToALNConfig() []ALNOption {
	out := []ALNOption{
		WithALNPort(c.Port),
		WithALNScheme(c.Scheme),
		WithALNUpdatePeriod(c.NodesListUpdatePeriod),
	}

	if c.Rack != "" {
		out = append(out, WithALNRack(c.Rack))
	}

	if c.Datacenter != "" {
		out = append(out, WithALNDatacenter(c.Datacenter))
	}

	if c.ALNHTTPClient != nil {
		out = append(out, WithALNHTTPClient(c.HTTPClient))
	}

	if c.IdleNodesListUpdatePeriod != 0 {
		out = append(out, WithALNIdleUpdatePeriod(c.IdleNodesListUpdatePeriod))
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

func WithLocalNodesReaderHTTPClient(httpClient *http.Client) Option {
	return func(config *Config) {
		config.ALNHTTPClient = httpClient
	}
}

func WithClientCertificateFile(certFile, keyFile string) Option {
	return func(config *Config) {
		config.ClientCertificate = NewFileCertificate(certFile, keyFile)
	}
}

func WithClientCertificate(certificate tls.Certificate) Option {
	return func(config *Config) {
		config.ClientCertificate = NewCertificate(certificate)
	}
}

func WithIgnoreServerCertificateError() Option {
	return func(config *Config) {
		config.IgnoreServerCertificateError = true
	}
}

func WithOptimizeHeaders() Option {
	return func(config *Config) {
		config.OptimizeHeaders = true
	}
}

func WithIdleNodesListUpdatePeriod(period time.Duration) Option {
	return func(config *Config) {
		config.IdleNodesListUpdatePeriod = period
	}
}
