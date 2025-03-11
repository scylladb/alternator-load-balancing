package common

import (
	"errors"
	"net/http"
	"time"
)

func PatchHTTPClientIdleConnectionTimeout(httpClient *http.Client, IdleConnectionTimeout time.Duration) error {
	if httpClient == nil {
		return errors.New("failed to patch http transport to set IdleConnectionTimeout: http client is nil")
	}
	httpTransport, ok := httpClient.Transport.(*http.Transport)
	if !ok {
		return errors.New("failed to patch http transport to set IdleConnectionTimeout: transport is not *http.Transport")
	}
	httpTransport.IdleConnTimeout = IdleConnectionTimeout
	return nil
}

func DefaultHTTPTransport() *http.Transport {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.IdleConnTimeout = defaultIdleConnectionTimeout
	transport.MaxIdleConns = 100
	return transport
}
