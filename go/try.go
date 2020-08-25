// To run this example, just run:
//       go build && ./go

package main
import (
    "github.com/aws/aws-sdk-go/service/dynamodb"
    "fmt"
    "time"
)

func main() {
    // Uncomment to use Amazon DynamoDB configured in ~/.aws/
    //    sess := session.Must(session.NewSessionWithOptions(session.Options{
    //        SharedConfigState: session.SharedConfigEnable,
    //    }))
    //    db := dynamodb.New(sess)

    // Use the local Alternator with our silly testing alternator/secret_pass
    // authentication - and the new load balancing code.
    alternator_nodes := NewAlternatorNodes("http", 8000, []string {"127.0.0.1"})
    sess := alternator_nodes.session("dog.scylladb.com", "alternator", "secret_pass")
    db := dynamodb.New(sess)

    for i := 1; i<20; i++ {
        time.Sleep(300*time.Millisecond)
        // Do the simplest possible request - DescribeEndpoints
        result, err := db.DescribeEndpoints(&dynamodb.DescribeEndpointsInput{})
        if err != nil {
            fmt.Println(err.Error())
        } else {
            fmt.Println("response:", *result.Endpoints[0].Address)
        }
    }
}
