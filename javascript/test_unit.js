const AWS = require('aws-sdk');
const alternator = require('./Alternator');

describe('Alternator Load Balancing Unit Tests', () => {
    afterEach(() => {
        // Clean up after each test
        alternator.done();
    });

    test('should expose FAKE_HOST constant', () => {
        expect(alternator.FAKE_HOST).toBe('dog.scylladb.com');
    });

    test('should expose agent object', () => {
        expect(alternator.agent).toBeDefined();
        expect(typeof alternator.agent.createConnection).toBe('function');
    });

    test('should initialize AWS configuration', () => {
        const initialNodes = ['127.0.0.1'];
        const port = 8000;
        const protocol = 'http';

        alternator.init(AWS, protocol, port, initialNodes);

        expect(AWS.config.endpoint).toBe(`${protocol}://${alternator.FAKE_HOST}:${port}`);
        expect(AWS.config.region).toBe('world');
        expect(AWS.config.httpOptions.agent).toBe(alternator.agent);
    });

    test('should support https protocol', () => {
        const initialNodes = ['127.0.0.1'];
        const port = 8443;
        const protocol = 'https';

        alternator.init(AWS, protocol, port, initialNodes);

        expect(AWS.config.endpoint).toBe(`${protocol}://${alternator.FAKE_HOST}:${port}`);
    });

    test('should accept multiple initial nodes', () => {
        const initialNodes = ['127.0.0.1', '127.0.0.2', '127.0.0.3'];
        const port = 8000;
        const protocol = 'http';

        expect(() => {
            alternator.init(AWS, protocol, port, initialNodes);
        }).not.toThrow();
    });

    test('done() should mark as finished', () => {
        alternator.init(AWS, 'http', 8000, ['127.0.0.1']);
        alternator.done();

        // The done flag is internal, but we can test that it doesn't throw
        expect(() => alternator.done()).not.toThrow();
    });
});