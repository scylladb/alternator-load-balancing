// The AlternatorNodes class defined here can be used to easily change any
// application using aws-sdk-go from using Amazon DynamoDB to use Alternator:
// While DynamoDB only has one "endpoint", this class helps us balance the
// requests between all the nodes in the Alternator cluster.
//
// Sending the requests to all Alternator nodes, not just one, is important
// for two reasons: High Availability (the failure of a single Alternator
// node should not prevent the client from proceeding) and Load Balancing
// over all Alternator nodes.
//
// To use this class, simply replace the creation of the AWS SDK
// session.Session - from a command like:
//
//    sess := session.Must(session.NewSessionWithOptions(session.Options{
//                SharedConfigState: session.SharedConfigEnable,
//
// To a command like:
//
//    alternator_nodes := NewAlternatorNodes("http", 8000, []string {"127.0.0.1"})
//    sess := alternator_nodes.session("dog.scylladb.com", "alternator", "secret_pass")
//
// And then just use this session normally - run db := dynamodb.New(sess)
// and then send DynamoDB requests to it.
//
// Above, the NewAlternatorNodes() parameters indicate a list of known
// Alternator nodes, and their common scheme (http or https) and port.
// This list can contain one or more nodes - we then periodically contact
// these nodes to fetch the full list of nodes using Alternator's
// "/localnodes" request. In the session() method, one needs to pick a "fake
// domain" which doesn't really mean anything (except it will be used as the
// Host header, and be returned by the DescribeEndpoints request), and the
// key and secret key for authentication to Alternator.
//
// Every request performed on this new session will pick a different live
// Alternator node to send it to. Despite us sending different requests
// to different nodes, Go will keep these connections cached and reuse them
// when we send another request to the same node (TODO: figure out the
// limitations of this caching. Where is it documented?).

package main

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

func NewAlternatorNodes(scheme string, port int, initialNodes ...string) *AlternatorNodes {
	return &AlternatorNodes{
		scheme: scheme,
		port:   port,
		nodes:  initialNodes,
	}
}

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

func (n *AlternatorNodes) Start(ctx context.Context, fetchInterval time.Duration) {
	ctx, cancelFunc := context.WithCancel(ctx)
	n.ctx = ctx
	n.cancelFunc = cancelFunc
	n.fetchInterval = fetchInterval

	go n.updateNodes()
}

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
