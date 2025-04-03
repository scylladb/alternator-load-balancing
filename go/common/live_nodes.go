package common

import (
	"context"
	"crypto/tls"
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
	defaultUpdatePeriod          = time.Second * 10
	defaultIdleConnectionTimeout = 6 * time.Hour
)

type AlternatorLiveNodes struct {
	liveNodes          atomic.Pointer[[]url.URL]
	initialNodes       []url.URL
	nextLiveNodeIdx    atomic.Uint64
	cfg                ALNConfig
	nextUpdate         atomic.Int64
	idleUpdaterStarted atomic.Bool
	ctx                context.Context
	stopFn             context.CancelFunc
	updateSignal       chan struct{}
}

type ALNConfig struct {
	Scheme       string
	Port         int
	Rack         string
	Datacenter   string
	UpdatePeriod time.Duration
	// Now often read /localnodes when no requests are going through
	IdleUpdatePeriod time.Duration
	HTTPClient       *http.Client
	// Makes it ignore server certificate errors
	IgnoreServerCertificateError bool
	ClientCertificateSource      *CertSource
}

func NewALNConfig() ALNConfig {
	return ALNConfig{
		Scheme:           defaultScheme,
		Port:             defaultPort,
		Rack:             "",
		Datacenter:       "",
		UpdatePeriod:     defaultUpdatePeriod,
		IdleUpdatePeriod: 0, // Don't update by default
		HTTPClient:       nil,
	}
}

type ALNOption func(config *ALNConfig)

func WithALNScheme(scheme string) ALNOption {
	return func(config *ALNConfig) {
		config.Scheme = scheme
	}
}

func WithALNPort(port int) ALNOption {
	return func(config *ALNConfig) {
		config.Port = port
	}
}

func WithALNRack(rack string) ALNOption {
	return func(config *ALNConfig) {
		config.Rack = rack
	}
}

func WithALNDatacenter(datacenter string) ALNOption {
	return func(config *ALNConfig) {
		config.Datacenter = datacenter
	}
}

func WithALNUpdatePeriod(period time.Duration) ALNOption {
	return func(config *ALNConfig) {
		config.UpdatePeriod = period
	}
}

func WithALNIdleUpdatePeriod(period time.Duration) ALNOption {
	return func(config *ALNConfig) {
		config.IdleUpdatePeriod = period
	}
}

func WithALNHTTPClient(client *http.Client) ALNOption {
	return func(config *ALNConfig) {
		config.HTTPClient = client
	}
}

func WithALNIgnoreServerCertificateError(value bool) ALNOption {
	return func(config *ALNConfig) {
		config.IgnoreServerCertificateError = value
	}
}

func WithALNClientCertificateFile(certFile, keyFile string) ALNOption {
	return func(config *ALNConfig) {
		config.ClientCertificateSource = NewFileCertificate(certFile, keyFile)
	}
}

func WithALNClientCertificate(certificate tls.Certificate) ALNOption {
	return func(config *ALNConfig) {
		config.ClientCertificateSource = NewCertificate(certificate)
	}
}

func WithALNClientCertificateSource(source *CertSource) ALNOption {
	return func(config *ALNConfig) {
		config.ClientCertificateSource = source
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

	if cfg.HTTPClient == nil {
		cfg.HTTPClient = &http.Client{
			Transport: NewHTTPTransport(cfg),
		}
	}

	nodes := make([]url.URL, len(initialNodes))
	for i, node := range initialNodes {
		parsed, err := url.Parse(fmt.Sprintf("%s://%s:%d", cfg.Scheme, node, cfg.Port))
		if err != nil {
			return nil, fmt.Errorf("invalid node URI: %v", err)
		}
		nodes[i] = *parsed
	}

	ctx, cancel := context.WithCancel(context.Background())
	out := &AlternatorLiveNodes{
		initialNodes: nodes,
		cfg:          cfg,
		ctx:          ctx,
		stopFn:       cancel,
		updateSignal: make(chan struct{}, 1),
	}

	out.liveNodes.Store(&nodes)
	return out, nil
}

func (aln *AlternatorLiveNodes) startIdleUpdater() {
	if aln.cfg.IdleUpdatePeriod == 0 {
		return
	}
	if aln.idleUpdaterStarted.CompareAndSwap(false, true) {
		go func() {
			t := time.NewTicker(aln.cfg.IdleUpdatePeriod)
			defer t.Stop()
			for {
				select {
				case <-aln.ctx.Done():
					return
				case <-t.C:
					aln.nextUpdate.Store(time.Now().UTC().Unix() + int64(aln.cfg.UpdatePeriod.Seconds()))
					aln.updateLiveNodes()
				case <-aln.updateSignal:
					aln.updateLiveNodes()
				}
			}
		}()
	}
}

func (aln *AlternatorLiveNodes) Start() {
	aln.startIdleUpdater()
}

func (aln *AlternatorLiveNodes) Stop() {
	if aln.stopFn != nil {
		aln.stopFn()
	}
}

// NextNode gets next node, check if node list needs to be updated and run updating routine if needed
func (aln *AlternatorLiveNodes) NextNode() url.URL {
	aln.startIdleUpdater()
	nextUpdate := aln.nextUpdate.Load()
	current := time.Now().UTC().Unix()
	if nextUpdate < current {
		if aln.nextUpdate.CompareAndSwap(nextUpdate, current+int64(aln.cfg.UpdatePeriod.Seconds())) {
			select {
			case aln.updateSignal <- struct{}{}:
			default:
			}
		}
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
	resp, err := aln.cfg.HTTPClient.Get(endpoint.String())
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
