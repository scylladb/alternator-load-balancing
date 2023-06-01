package alternatorlb

import (
	"context"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"net/url"
	"sort"
	"sync"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/smithy-go/middleware"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// AlternatorNodes holds the configuration for the load balanced alternator nodes as well as some locks for the load balancing thread.
type AlternatorNodes struct {
	fetchInterval time.Duration
	scheme        string
	port          int

	nodes         []string
	nextNodeIndex int

	mutex      sync.Mutex
	ctx        context.Context
	cancelFunc context.CancelFunc
}

// NewAlternatorNodes creates a new, unstarted instance of the alternator nodes loadbalancing.
func NewAlternatorNodes(scheme string, port int, initialNodes ...string) *AlternatorNodes {
	return &AlternatorNodes{
		scheme: scheme,
		port:   port,
		nodes:  initialNodes,
	}
}

// Config produces a conf for the AWS SDK that will integrate the alternator loadbalancing with the AWS SDK.
func (n *AlternatorNodes) Config(fake_domain string, key string, secret_key string) aws.Config {
	fake_url := fmt.Sprintf("%s://%s:%d", n.scheme, fake_domain, n.port)

	return aws.Config{
		EndpointResolverWithOptions: staticEndpointResolver(fake_url),
		// Region is used in the signature algorithm so prevent request sent
		// to one region to be forward by an attacker to a different region.
		// But Alternator doesn't check it. It can be anything.
		Region: "whatever",
		// The third credential below, the session token, is only used for
		// temporary credentials, and is not supported by Alternator anyway.
		Credentials: credentials.NewStaticCredentialsProvider(key, secret_key, ""),

		APIOptions: []func(*middleware.Stack) error{
			func(m *middleware.Stack) error {
				return m.Finalize.Add(n.loadBalancerMiddleware(fake_domain), middleware.Before)
			},
		},
	}
}

// Start will start the loadbalancing thread that keep available alternator instances in sync and selectes the next instance for load balancing.
func (n *AlternatorNodes) Start(ctx context.Context, fetchInterval time.Duration) {
	ctx, cancelFunc := context.WithCancel(ctx)
	n.ctx = ctx
	n.cancelFunc = cancelFunc
	n.fetchInterval = fetchInterval

	go n.updateNodes()
}

// Stop will stop the loadbalancing thread and should be called once you are done with the AWS SDK session.
func (n *AlternatorNodes) Stop() {
	n.cancelFunc()
}

func (n *AlternatorNodes) pickNode() string {
	n.mutex.Lock()
	ret := n.nodes[n.nextNodeIndex]
	n.nextNodeIndex++
	if n.nextNodeIndex == len(n.nodes) {
		n.nextNodeIndex = 0
	}
	n.mutex.Unlock()
	return ret
}

func (n *AlternatorNodes) updateNodes() {
	for {
		select {
		case <-n.ctx.Done():
			return
		default:
			if err := n.doUpdateNodes(); err != nil {
				fmt.Printf("Alternator node updating encountered an error: '%v'\n", err)
			}
		}

		time.Sleep(n.fetchInterval)
	}
}

func (n *AlternatorNodes) doUpdateNodes() error {
	// Contact one of the already known nodes, to fetch a new list of known nodes.
	req, err := http.NewRequestWithContext(
		n.ctx,
		http.MethodGet,
		fmt.Sprintf("%s://%s:%d/localnodes", n.scheme, n.pickNode(), n.port),
		http.NoBody,
	)
	if err != nil {
		return err
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}

	defer resp.Body.Close()
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return err
	}

	var fetchedNodes []string
	if err := json.Unmarshal(body, &fetchedNodes); err != nil {
		return err
	}
	// Sort the list because it can be returned in a different order every time, making "next" unreliable.
	sort.Strings(fetchedNodes)

	n.mutex.Lock()
	n.nodes = fetchedNodes
	if n.nextNodeIndex >= len(n.nodes) {
		n.nextNodeIndex = 0
	}
	n.mutex.Unlock()

	return nil
}

func (n *AlternatorNodes) loadBalancerMiddleware(domain string) middleware.FinalizeMiddleware {
	host := fmt.Sprintf("%s:%d", domain, n.port)
	middlewareFunc := func(ctx context.Context, in middleware.FinalizeInput, next middleware.FinalizeHandler) (middleware.FinalizeOutput, middleware.Metadata, error) {
		switch v := in.Request.(type) {
		case *smithyhttp.Request:
			if v.URL != nil && v.URL.Host == host {
				pickedNode := n.pickNode()
				new_url := url.URL{Scheme: n.scheme, Host: fmt.Sprintf("%s:%d", pickedNode, n.port)}
				fmt.Printf("Alternator load balacing %s -> %s\n", v.URL.String(), new_url.String())
				*v.URL = new_url
				// The request is already signed with a signature including
				// fake_host. We must set the "Host" header in the request
				// to the same fake_host, or the signatures won't match.
				// Note that HTTPRequest ignores the "Host" header - and instead
				// has a spearate "Host" member:
				v.Host = host
			}
		}

		return next.HandleFinalize(ctx, in)
	}

	return middleware.FinalizeMiddlewareFunc("alternator-lb", middlewareFunc)
}

func staticEndpointResolver(url string) aws.EndpointResolverWithOptionsFunc {
	return func(service, region string, options ...interface{}) (aws.Endpoint, error) {
		if service == dynamodb.ServiceID {
			return aws.Endpoint{
				PartitionID:       "aws",
				URL:               url,
				HostnameImmutable: true,
			}, nil
		}

		return aws.Endpoint{}, &aws.EndpointNotFoundError{}
	}
}
