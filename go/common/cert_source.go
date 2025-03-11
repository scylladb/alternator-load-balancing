package common

import (
	"crypto/tls"
	"fmt"
	"net/http"
	"os"
	"sync"
	"time"
)

type CertSource struct {
	certPath string
	keyPath  string
	cert     *tls.Certificate
	mutex    sync.Mutex
	modTime  time.Time
}

func NewFileCertificate(certPath, keyPath string) *CertSource {
	return &CertSource{
		certPath: certPath,
		keyPath:  keyPath,
	}
}

func NewCertificate(certificate tls.Certificate) *CertSource {
	return &CertSource{
		cert: &certificate,
	}
}

func (c *CertSource) GetCertificate() (*tls.Certificate, error) {
	if c.certPath == "" {
		return c.cert, nil
	}

	c.mutex.Lock()
	defer c.mutex.Unlock()

	certStat, err := os.Stat(c.certPath)
	if err != nil {
		err = fmt.Errorf("failed to stat certificate file %s: %w", c.certPath, err)
		if c.cert != nil {
			LogError(err)
			return c.cert, nil
		}
		return nil, err
	}

	if c.cert != nil && certStat.ModTime().Equal(c.modTime) {
		return c.cert, nil // Return cached certificate if unchanged
	}

	cert, err := tls.LoadX509KeyPair(c.certPath, c.keyPath)
	if err != nil {
		err = fmt.Errorf("failed to load certificate file %s: %w", c.certPath, err)
		if c.cert != nil {
			LogError(err)
			return c.cert, nil
		}
		return nil, err
	}

	c.cert = &cert
	c.modTime = certStat.ModTime()
	return c.cert, nil
}

func (c *CertSource) PatchHTTPTransport(transport *http.Transport) {
	if transport.TLSClientConfig == nil {
		transport.TLSClientConfig = &tls.Config{}
	}
	transport.TLSClientConfig.GetClientCertificate = func(_ *tls.CertificateRequestInfo) (*tls.Certificate, error) {
		return c.GetCertificate()
	}
}
