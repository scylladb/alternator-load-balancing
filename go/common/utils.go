package common

import (
	"net/http"
)

func DefaultHTTPTransport() *http.Transport {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.IdleConnTimeout = defaultIdleConnectionTimeout
	transport.MaxIdleConns = 100
	return transport
}
