package alternator_loadbalancing

import (
	"fmt"
	"net/url"
	"strings"

	aln "alternator_live_nodes"

	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/aws/credentials"
	"github.com/aws/aws-sdk-go/aws/request"
	"github.com/aws/aws-sdk-go/aws/session"
	"github.com/aws/aws-sdk-go/service/dynamodb"
)

type AlternatorLB struct {
	nodes  *aln.AlternatorLiveNodes
	port   int
	scheme string
}

type Config = aln.Config
type Option = aln.Option

var (
	WithScheme       = aln.WithScheme
	WithPort         = aln.WithPort
	WithRack         = aln.WithRack
	WithDatacenter   = aln.WithDatacenter
	WithHTTPClient   = aln.WithHTTPClient
	WithUpdatePeriod = aln.WithUpdatePeriod
)

func NewAlternatorLB(initialNodes []string, options ...Option) (*AlternatorLB, error) {
	nodes, err := aln.NewAlternatorLiveNodes(initialNodes, options...)
	if err != nil {
		return nil, err
	}

	// Get scheme and port from config
	cfg := aln.NewConfig()
	for _, opt := range options {
		if opt == nil {
			opt(&cfg)
		}
	}

	return &AlternatorLB{
		nodes:  nodes,
		port:   cfg.Port,
		scheme: cfg.Scheme,
	}, nil
}

func (lb *AlternatorLB) nextNode() url.URL {
	return lb.nodes.NextNode()
}

func (lb *AlternatorLB) CheckIfRackAndDatacenterSetCorrectly() error {
	return lb.nodes.CheckIfRackAndDatacenterSetCorrectly()
}

func (lb *AlternatorLB) CheckIfRackDatacenterFeatureIsSupported() (bool, error) {
	return lb.nodes.CheckIfRackDatacenterFeatureIsSupported()
}

func (lb *AlternatorLB) PatchSession(s *session.Session) {
	s.Handlers.Send.PushFront(func(r *request.Request) {
		if strings.Contains(r.HTTPRequest.URL.Host, "dynamodb") {
			node := lb.nodes.NextNode()
			r.HTTPRequest.Host = node.Host
			r.HTTPRequest.URL.Host = node.Host
			r.HTTPRequest.URL.Scheme = node.Scheme
		}
	})
	return
}

func (lb *AlternatorLB) NewDynamoDB(key string, secret string) (*dynamodb.DynamoDB, error) {
	fakeUrl := fmt.Sprintf("%s://%s:%d", lb.scheme, "dynamodb.fake.alterntor.cluster.node", lb.port)
	cfg := aws.Config{
		Endpoint: aws.String(fakeUrl),
		// Region is used in the signature algorithm so prevent request sent
		// to one region to be forward by an attacker to a different region.
		// But Alternator doesn't check it. It can be anything.
		Region: aws.String("whatever"),
		// The third credential below, the session token, is only used for
		// temporary credentials, and is not supported by Alternator anyway.
	}

	if key != "" || secret != "" {
		cfg.Credentials = credentials.NewStaticCredentials(key, secret, "")
	}

	sess, err := session.NewSession(&cfg)
	if err != nil {
		return nil, err
	}
	lb.PatchSession(sess)
	return dynamodb.New(sess), nil
}
