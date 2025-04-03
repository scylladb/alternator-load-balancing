package common

import (
	"crypto/tls"
	"errors"
	"io"
	"net/http"
	"time"
)

type Config struct {
	Port                    int
	Scheme                  string
	Rack                    string
	Datacenter              string
	AWSRegion               string
	NodesListUpdatePeriod   time.Duration
	AccessKeyID             string
	SecretAccessKey         string
	HTTPClient              *http.Client
	ALNHTTPClient           *http.Client
	ClientCertificateSource *CertSource
	// Makes it ignore server certificate errors
	IgnoreServerCertificateError bool
	// OptimizeHeaders - when true removes unnecessary http headers reducing network footprint
	OptimizeHeaders bool
	// Update node list when no requests are running
	IdleNodesListUpdatePeriod time.Duration
	// A key writer for pre master key: https://wiki.wireshark.org/TLS#using-the-pre-master-secret
	KeyLogWriter io.Writer
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

func (c *Config) ToALNConfig() ALNConfig {
	cfg := NewALNConfig()
	for _, opt := range c.ToALNOptions() {
		opt(&cfg)
	}
	return cfg
}

func (c *Config) ToALNOptions() []ALNOption {
	out := []ALNOption{
		WithALNPort(c.Port),
		WithALNScheme(c.Scheme),
		WithALNUpdatePeriod(c.NodesListUpdatePeriod),
		WithALNIgnoreServerCertificateError(c.IgnoreServerCertificateError),
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

	if c.ClientCertificateSource != nil {
		out = append(out, WithALNClientCertificateSource(c.ClientCertificateSource))
	}

	if c.KeyLogWriter != nil {
		out = append(out, WithALNKeyLogWriter(c.KeyLogWriter))
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
		config.ClientCertificateSource = NewFileCertificate(certFile, keyFile)
	}
}

func WithClientCertificate(certificate tls.Certificate) Option {
	return func(config *Config) {
		config.ClientCertificateSource = NewCertificate(certificate)
	}
}

func WithClientCertificateSource(source *CertSource) Option {
	return func(config *Config) {
		config.ClientCertificateSource = source
	}
}

func WithIgnoreServerCertificateError(value bool) Option {
	return func(config *Config) {
		config.IgnoreServerCertificateError = value
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

func WithKeyLogWriter(writer io.Writer) Option {
	return func(config *Config) {
		config.KeyLogWriter = writer
	}
}

func PatchHTTPClient(config Config, client interface{}) error {
	httpClient, ok := client.(*http.Client)
	if !ok {
		return errors.New("config is not a http client")
	}
	alnConfig := config.ToALNConfig()

	if !config.IgnoreServerCertificateError && config.ClientCertificateSource == nil && !config.OptimizeHeaders && config.KeyLogWriter == nil {
		return nil
	}

	if httpClient.Transport == nil {
		httpClient.Transport = DefaultHTTPTransport()
	}
	httpTransport, ok := httpClient.Transport.(*http.Transport)
	if !ok {
		return errors.New("failed to patch http transport for ignore server certificate")
	}
	PatchBasicHTTPTransport(alnConfig, httpTransport)

	if config.OptimizeHeaders {
		allowedHeaders := []string{"Host", "X-Amz-Target", "Content-Length", "Accept-Encoding"}
		if config.AccessKeyID != "" {
			allowedHeaders = append(allowedHeaders, "Authorization", "X-Amz-Date")
		}
		httpClient.Transport = NewHeaderWhiteListingTransport(httpTransport, allowedHeaders...)
	}
	return nil
}
