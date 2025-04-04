package common

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"net/http"
	"os"
)

func DefaultHTTPTransport() *http.Transport {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.IdleConnTimeout = defaultIdleConnectionTimeout
	return transport
}

func NewHTTPTransport(config ALNConfig) *http.Transport {
	transport := DefaultHTTPTransport()
	PatchBasicHTTPTransport(config, transport)
	return transport
}

func PatchBasicHTTPTransport(config ALNConfig, transport *http.Transport) {
	transport.IdleConnTimeout = defaultIdleConnectionTimeout
	transport.MaxIdleConns = config.MaxIdleHTTPConnections

	if transport.TLSClientConfig == nil {
		transport.TLSClientConfig = &tls.Config{}
	}

	if config.KeyLogWriter != nil {
		transport.TLSClientConfig.KeyLogWriter = config.KeyLogWriter
	}

	if config.IgnoreServerCertificateError {
		transport.TLSClientConfig.InsecureSkipVerify = true
		transport.TLSClientConfig.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
			return nil
		}
	}

	if config.TLSSessionCache != nil {
		transport.TLSClientConfig.ClientSessionCache = config.TLSSessionCache
	}

	if config.ClientCertificateSource != nil {
		transport.TLSClientConfig.GetClientCertificate = func(_ *tls.CertificateRequestInfo) (*tls.Certificate, error) {
			return config.ClientCertificateSource.GetCertificate()
		}
	}
}

func LogError(err error) {
	_, _ = fmt.Fprintf(os.Stderr, "ERROR: %s", err.Error())
}
