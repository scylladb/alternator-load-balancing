package common

import (
	"net/http"
	"slices"
	"strings"
)

type HeaderWhiteListing struct {
	allowedHeaders []string
	original       http.RoundTripper
}

func NewHeaderWhiteListingTransport(original http.RoundTripper, allowedHeaders ...string) *HeaderWhiteListing {
	for id, h := range allowedHeaders {
		allowedHeaders[id] = strings.ToLower(h)
	}
	return &HeaderWhiteListing{
		allowedHeaders: allowedHeaders,
		original:       original,
	}
}

func (h HeaderWhiteListing) RoundTrip(r *http.Request) (*http.Response, error) {
	newHeaders := http.Header{}
	for headerName := range r.Header {
		if slices.Contains(h.allowedHeaders, strings.ToLower(headerName)) {
			newHeaders.Set(headerName, r.Header.Get(headerName))
		}
	}
	r.Header = newHeaders
	return h.original.RoundTrip(r)
}

var _ http.RoundTripper = HeaderWhiteListing{}
