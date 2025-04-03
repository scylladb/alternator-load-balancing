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
	transport.MaxIdleConns = 100
	return transport
}

func NewHTTPTransport(config ALNConfig) *http.Transport {
	transport := DefaultHTTPTransport()
	PatchBasicHTTPTransport(config, transport)
	return transport
}

func PatchBasicHTTPTransport(config ALNConfig, transport *http.Transport) {
	needsToBePatched := config.IgnoreServerCertificateError || config.ClientCertificateSource != nil

	transport.IdleConnTimeout = defaultIdleConnectionTimeout
	transport.MaxIdleConns = 100

	if !needsToBePatched {
		return
	}

	if transport.TLSClientConfig == nil {
		transport.TLSClientConfig = &tls.Config{}
	}

	if config.IgnoreServerCertificateError {
		transport.TLSClientConfig.InsecureSkipVerify = true
		transport.TLSClientConfig.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
			return nil
		}
	}

	if config.ClientCertificateSource != nil {
		config.ClientCertificateSource.PatchHTTPTransport(transport)
	}
}

func LogError(err error) {
	_, _ = fmt.Fprintf(os.Stderr, "ERROR: %s", err.Error())
}

func LogErrorf(format string, args ...interface{}) {
	_, _ = fmt.Fprintf(os.Stderr, "ERROR:"+format, args...)
}
