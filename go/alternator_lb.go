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
    "github.com/aws/aws-sdk-go/aws/session"
    "github.com/aws/aws-sdk-go/aws/request"
    "github.com/aws/aws-sdk-go/aws"
    "github.com/aws/aws-sdk-go/aws/credentials"
    "fmt"
    "time"
    "sync"
    "net/url"
    "net/http"
    "io/ioutil"
    "strings"
    "sort"
)

type AlternatorNodes struct {
    scheme string
    port int
    nodes []string
    next int           // for round-robin load-balancing of 'nodes'
    mutex sync.Mutex
}

func NewAlternatorNodes(scheme string, port int, nodes []string) *AlternatorNodes {
    ret := &AlternatorNodes{scheme: scheme, port: port, nodes: nodes}
    go ret.update_thread()
    // TODO: how can we stop the background thread if the object is destructed?
    return ret
}

func (this *AlternatorNodes) pickone() string {
    this.mutex.Lock()
    ret := this.nodes[this.next]
    this.next++
    if this.next == len(this.nodes) {
        this.next = 0
    }
    this.mutex.Unlock()
    return ret
}

func (this *AlternatorNodes) update_thread() {
    fmt.Println("livenodes.update() starting with", this.nodes)
    for {
        // Contact one of the already known nodes, to fetch a new list of known
        // nodes.
        url := fmt.Sprintf("%s://%s:%d/localnodes", this.scheme, this.pickone(), this.port)
        resp, err := http.Get(url)
        if err != nil {
            fmt.Println(err.Error())
        } else {
            defer resp.Body.Close()
            body, err := ioutil.ReadAll(resp.Body)
            if err != nil {
                fmt.Println(err.Error())
            } else {
                a := strings.Split(strings.Trim(string(body),"[]"), ",")
                for i :=  range a {
                    a[i]  = strings.Trim(a[i], "\"")
                }
                // sort the list because it can be returned in a different
                // order every time, making "next" unreliable.
                sort.Strings(a)
                this.mutex.Lock()
                this.nodes = a
                if this.next >= len(this.nodes) {
                    this.next = 0
                }
                this.mutex.Unlock()
                fmt.Println("livenodes.update() updated to ", this.nodes)
            }
        }
        time.Sleep(1*time.Second)
    }
}

// session() creates a session.Session object, replacing the
// traditional call to "session.Must(session.NewSession(&cfg)".
func (this *AlternatorNodes) session(
            fake_domain string,
            key string,
            secret_key string) *session.Session {
    fake_url := fmt.Sprintf("%s://%s:%d", this.scheme, fake_domain, this.port)
    cfg := aws.Config{
        Endpoint: aws.String(fake_url),
        // Region is used in the signature algorithm so prevent request sent
        // to one region to be forward by an attacker to a different region.
        // But Alternator doesn't check it. It can be anything.
        Region:   aws.String("whatever"),
        // The third credential below, the session token, is only used for
        // temporary credentials, and is not supported by Alternator anyway.
        Credentials: credentials.NewStaticCredentials(key, secret_key, ""),
    }
    sess := session.Must(session.NewSession(&cfg))
    sess.Handlers.Send.PushFront(func(r *request.Request) {
        // Only load-balance requests to the fake_domain.
        fake_host := fmt.Sprintf("%s:%d", fake_domain, this.port)
        if r.HTTPRequest.URL.Host == fake_host {
            new_url := url.URL{Scheme: this.scheme, Host: fmt.Sprintf("%s:%d", this.pickone(), this.port)}
            fmt.Printf("Alternator load balacing %s -> %s\n", r.HTTPRequest.URL.String(), new_url.String())
            *r.HTTPRequest.URL = new_url
            // The request is already signed with a signature including
            // fake_host. We must set the "Host" header in the request
            // to the same fake_host, or the signatures won't match.
            // Note that HTTPRequest ignores the "Host" header - and instead
            // has a spearate "Host" member:
            r.HTTPRequest.Host = fake_host
        }
    })
    return sess
}
