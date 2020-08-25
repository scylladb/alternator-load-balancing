// Example usage:
// AWS = require('aws-sdk');
// alternator = require('./Alternator');
// alternator.init(AWS, "http", 8000, ["127.0.0.1"]);
// (...)
// alternator.done();

var AWS = require("aws-sdk");
var http = require("http");
var https = require("https");
var dns = require("dns");

const FAKE_HOST = "dog.scylladb.com";
exports.FAKE_HOST = FAKE_HOST;

var protocol;
var hostIdx = 0;
var hosts;
var port;
var done = false;
var updatePromise;

var agent = new http.Agent;

var oldCreateConnection = agent.createConnection;
agent.createConnection = function(options, callback = null) {
    options.lookup = function(hostname, options = null, callback) {
        if (hostname == FAKE_HOST) {
            var host = hosts[hostIdx];
            hostIdx = (hostIdx + 1) % hosts.length;
            console.log("Picked", host);
            return dns.lookup(host, options, callback);
        }
        return dns.lookup(hostname, options, callback);
    };
    return oldCreateConnection(options, callback);
};

exports.agent = agent;

async function updateHosts() {
    if (done) {
        return;
    }
    let proto = (protocol == "https") ? https : http;
    proto.get(protocol + "://" + hosts[hostIdx] + ":" + 8000 + "/localnodes", (resp) => {
        resp.on('data', (payload) => {
            payload = JSON.parse(payload);
            hosts = payload;
            console.log("Hosts updated to", hosts);
        });
    });
    await new Promise(r => setTimeout(r, 1000));
    return updateHosts();
}

exports.init = function(AWS, initialProtocol, initialPort, initialHosts) {
    protocol = initialProtocol;
    hosts = initialHosts;
    port = initialPort;
    AWS.config.update({
    region: "world",
    endpoint: protocol + "://" + FAKE_HOST + ":" + initialPort,
    httpOptions:{
        agent: agent
    }
    });
    done = false;
    updatePromise = updateHosts().catch((error) => {
        console.error(error);
    });;
}

exports.done = function() {
    done = true;
}
