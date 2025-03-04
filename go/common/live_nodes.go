package common

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/ioutil"
	"net/http"
	"net/url"
	"sync/atomic"
	"time"
)

const (
	defaultUpdatePeriod = time.Second * 10
)

type AlternatorLiveNodes struct {
	liveNodes       atomic.Pointer[[]url.URL]
	initialNodes    []url.URL
	nextLiveNodeIdx atomic.Uint64
	updating        atomic.Bool
	cfg             ALNConfig
	nextUpdate      atomic.Int64
}

type ALNConfig struct {
	Scheme       string
	Port         int
	Rack         string
	Datacenter   string
	UpdatePeriod time.Duration
	HTTPClient   *http.Client
}

func NewALNConfig() ALNConfig {
	return ALNConfig{
		Scheme:       defaultScheme,
		Port:         defaultPort,
		Rack:         "",
		Datacenter:   "",
		UpdatePeriod: defaultUpdatePeriod,
		HTTPClient:   http.DefaultClient,
	}
}

type ALNOption func(ALNConfig *ALNConfig)

func WithALNScheme(scheme string) ALNOption {
	return func(ALNConfig *ALNConfig) {
		ALNConfig.Scheme = scheme
	}
}

func WithALNPort(port int) ALNOption {
	return func(ALNConfig *ALNConfig) {
		ALNConfig.Port = port
	}
}

func WithALNRack(rack string) ALNOption {
	return func(ALNConfig *ALNConfig) {
		ALNConfig.Rack = rack
	}
}

func WithALNDatacenter(datacenter string) ALNOption {
	return func(ALNConfig *ALNConfig) {
		ALNConfig.Datacenter = datacenter
	}
}

func WithALNUpdatePeriod(updatePeriod time.Duration) ALNOption {
	return func(ALNConfig *ALNConfig) {
		ALNConfig.UpdatePeriod = updatePeriod
	}
}

func WithALNHTTPClient(client *http.Client) ALNOption {
	return func(ALNConfig *ALNConfig) {
		ALNConfig.HTTPClient = client
	}
}

func NewAlternatorLiveNodes(initialNodes []string, options ...ALNOption) (*AlternatorLiveNodes, error) {
	if len(initialNodes) == 0 {
		return nil, errors.New("liveNodes cannot be empty")
	}

	cfg := NewALNConfig()
	for _, opt := range options {
		opt(&cfg)
	}

	nodes := make([]url.URL, len(initialNodes))
	for i, node := range initialNodes {
		parsed, err := url.Parse(fmt.Sprintf("%s://%s:%d", cfg.Scheme, node, cfg.Port))
		if err != nil {
			return nil, fmt.Errorf("invalid node URI: %v", err)
		}
		nodes[i] = *parsed
	}

	out := &AlternatorLiveNodes{
		initialNodes: nodes,
		cfg:          cfg,
	}

	out.liveNodes.Store(&nodes)
	return out, nil
}

func (aln *AlternatorLiveNodes) UpdateOnce() bool {
	if !aln.updating.CompareAndSwap(false, true) {
		return false
	}
	aln.updateLiveNodes()
	aln.updating.Store(false)
	return true
}

// NextNode gets next node, check if node list needs to be updated and run updating routine if needed
func (aln *AlternatorLiveNodes) NextNode() url.URL {
	nextUpdate := aln.nextUpdate.Load()
	current := time.Now().UTC().Unix()
	if nextUpdate < current {
		if !aln.updating.CompareAndSwap(false, true) {
			return aln.nextNode()
		}
		go func() {
			defer func() {
				aln.nextUpdate.Store(time.Now().UTC().Unix() + int64(aln.cfg.UpdatePeriod.Seconds()))
				aln.updating.Store(false)
			}()
			aln.updateLiveNodes()
		}()
	}
	return aln.nextNode()
}

func (aln *AlternatorLiveNodes) nextNode() url.URL {
	nodes := *aln.liveNodes.Load()
	if len(nodes) == 0 {
		nodes = aln.initialNodes
	}
	return nodes[aln.nextLiveNodeIdx.Add(1)%uint64(len(nodes))]
}

func (aln *AlternatorLiveNodes) nextAsURLWithPath(path, query string) *url.URL {
	base := aln.nextNode()
	newURL := base
	newURL.Path = path
	if query != "" {
		newURL.RawQuery = query
	}
	return &newURL
}

func (aln *AlternatorLiveNodes) updateLiveNodes() {
	newNodes, err := aln.getNodes(aln.nextAsLocalNodesURL())
	if err == nil && len(newNodes) > 0 {
		aln.liveNodes.Store(&newNodes)
	}
}

func (aln *AlternatorLiveNodes) getNodes(endpoint *url.URL) ([]url.URL, error) {
	resp, err := http.Get(endpoint.String())
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, errors.New("non-200 response")
	}
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	var nodes []string
	if err := json.Unmarshal(body, &nodes); err != nil {
		return nil, err
	}

	var uris []url.URL
	for _, node := range nodes {
		nodeURL, err := url.Parse(fmt.Sprintf("%s://%s:%d", aln.cfg.Scheme, node, aln.cfg.Port))
		if err != nil {
			continue
		}
		uris = append(uris, *nodeURL)
	}
	return uris, nil
}

func (aln *AlternatorLiveNodes) nextAsLocalNodesURL() *url.URL {
	query := ""
	if aln.cfg.Rack != "" {
		query += "rack=" + aln.cfg.Rack
	}
	if aln.cfg.Datacenter != "" {
		if query != "" {
			query += "&"
		}
		query += "dc=" + aln.cfg.Datacenter
	}
	return aln.nextAsURLWithPath("/localnodes", query)
}

func (aln *AlternatorLiveNodes) CheckIfRackAndDatacenterSetCorrectly() error {
	if aln.cfg.Rack == "" && aln.cfg.Datacenter == "" {
		return nil
	}
	newNodes, err := aln.getNodes(aln.nextAsLocalNodesURL())
	if err != nil {
		return fmt.Errorf("failed to read list of nodes: %v", err)
	}
	if len(newNodes) == 0 {
		return errors.New("node returned empty list, datacenter or rack might be incorrect")
	}
	return nil
}

func (aln *AlternatorLiveNodes) CheckIfRackDatacenterFeatureIsSupported() (bool, error) {
	baseURI := aln.nextAsURLWithPath("/localnodes", "")
	fakeRackURI := aln.nextAsURLWithPath("/localnodes", "rack=fakeRack")

	hostsWithFakeRack, err := aln.getNodes(fakeRackURI)
	if err != nil {
		return false, err
	}
	hostsWithoutRack, err := aln.getNodes(baseURI)
	if err != nil {
		return false, err
	}
	if len(hostsWithoutRack) == 0 {
		return false, errors.New("host returned empty list")
	}

	return len(hostsWithFakeRack) != len(hostsWithoutRack), nil
}
