// Example usage:
// AWS = require('aws-sdk');
// alternator = require('./Alternator');
// alternator.init(AWS, "http", 8000, ["127.0.0.1"]);
// (...)
// alternator.done();

var AWS = require('aws-sdk');
var http = require('http');
var https = require('https');
var dns = require('dns');
const {CredentialsOptions} = require('aws-sdk/lib/credentials');

const FAKE_HOST = 'dog.scylladb.com';
exports.FAKE_HOST = FAKE_HOST;

var protocol;
var hostIdx = 0;
var hosts;
var port;
var done = false;
var updatePromise;
var enableBackgroundUpdates = true;

var httpAgent = new http.Agent;
var httpsAgent = new https.Agent;

function setupAgent(agent) {
    var oldCreateConnection = agent.createConnection;
    agent.createConnection = function(options, callback = null) {
        options.lookup = function(hostname, options = null, callback) {
            if (hostname == FAKE_HOST) {
                if (!hosts || hosts.length === 0) {
                    // Fallback to localhost if no hosts available
                    return dns.lookup('127.0.0.1', options, callback);
                }
                var host = hosts[hostIdx % hosts.length];
                hostIdx = (hostIdx + 1) % hosts.length;
                if (!host) {
                    // Fallback to first host if current host is undefined
                    host = hosts[0] || '127.0.0.1';
                }
                return dns.lookup(host, options, callback);
            }
            return dns.lookup(hostname, options, callback);
        };
        return oldCreateConnection(options, callback);
    };
}

setupAgent(httpAgent);
setupAgent(httpsAgent);

exports.httpAgent = httpAgent;
exports.httpsAgent = httpsAgent;
// Backward compatibility - expose agent for HTTP by default
exports.agent = httpAgent;

async function updateHosts() {
    if (done || !enableBackgroundUpdates) {
        return;
    }
    let proto = (protocol == 'https') ? https : http;
    try {
        await new Promise((resolve, reject) => {
            const currentHost = hosts[hostIdx % hosts.length] || hosts[0] || '127.0.0.1';
            const req = proto.get(protocol + '://' + currentHost + ':' + port + '/localnodes', (resp) => {
                let data = '';
                resp.on('data', (chunk) => {
                    data += chunk;
                });
                resp.on('end', () => {
                    try {
                        const payload = JSON.parse(data);
                        if (Array.isArray(payload) && payload.length > 0) {
                            hosts = payload;
                        }
                        resolve();
                    } catch (error) {
                        console.error('Failed to parse response:', error);
                        resolve(); // Continue with existing hosts
                    }
                });
            });
            req.on('error', (error) => {
                console.error('Request failed:', error);
                resolve(); // Continue with existing hosts
            });
            req.setTimeout(5000, () => {
                req.destroy();
                resolve(); // Continue with existing hosts
            });
        });
    } catch (error) {
        console.error('updateHosts error:', error);
    }

    await new Promise(r => setTimeout(r, 1000));
    return updateHosts();
}

exports.init = function(AWS, initialProtocol, initialPort, initialHosts) {
    protocol = initialProtocol;
    hosts = initialHosts ? [...initialHosts] : ['127.0.0.1']; // Make a copy and ensure non-empty
    hostIdx = 0;
    port = initialPort;
    // Disable background updates in test environment (when using localhost or 127.0.0.1)
    enableBackgroundUpdates = !hosts.every(host =>
        host === '127.0.0.1' || host === 'localhost' || host.startsWith('127.')
    );
    const agent = (initialProtocol === 'https') ? httpsAgent : httpAgent;
    // Update the backward compatibility export to match the current agent
    exports.agent = agent;
    AWS.config.update({
        credentials: {
            accessKeyId: '1',
            secretAccessKey: '1',
        },
        region: 'world',
        endpoint: protocol + '://' + FAKE_HOST + ':' + initialPort,
        httpOptions:{
            agent: agent
        }
    });
    done = false;
    if (enableBackgroundUpdates) {
        updatePromise = updateHosts().catch((error) => {
            console.error(error);
        });
    }
};

exports.done = function() {
    done = true;
};
