package common

import (
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

func LogError(err error) {
	_, _ = fmt.Fprintf(os.Stderr, "ERROR: %s", err.Error())
}

func LogErrorf(format string, args ...interface{}) {
	_, _ = fmt.Fprintf(os.Stderr, "ERROR:"+format, args...)
}
