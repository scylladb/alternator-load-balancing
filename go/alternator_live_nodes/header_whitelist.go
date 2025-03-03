package alternator_live_nodes

import (
	"net/http"
	"slices"
)

type HeaderWhiteListing struct {
	allowedHeaders []string
	original       http.RoundTripper
}

func NewHeaderWhiteListing(original http.RoundTripper, allowedHeaders ...string) *HeaderWhiteListing {
	return &HeaderWhiteListing{
		allowedHeaders: allowedHeaders,
		original:       original,
	}
}

func (h HeaderWhiteListing) RoundTrip(r *http.Request) (*http.Response, error) {
	newHeaders := http.Header{}
	for headerName := range r.Header {
		if slices.Contains(h.allowedHeaders, headerName) {
			newHeaders.Set(headerName, r.Header.Get(headerName))
		}
	}
	r.Header = newHeaders
	return h.original.RoundTrip(r)
}

var _ http.RoundTripper = HeaderWhiteListing{}
