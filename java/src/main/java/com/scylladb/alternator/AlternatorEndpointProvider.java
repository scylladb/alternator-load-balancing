package com.scylladb.alternator;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import software.amazon.awssdk.endpoints.Endpoint;
import software.amazon.awssdk.services.dynamodb.endpoints.DynamoDbEndpointParams;
import software.amazon.awssdk.services.dynamodb.endpoints.DynamoDbEndpointProvider;

// AWS Java SDK v2 allows providing a DynamoDbEndpointProvider which can
// choose a different endpoint for each request. Here we implement an
// AlternatorEndpointProvider, which maintains up-to-date knowledge of the
// live nodes in Alternator data center (by holding a AlternatorLiveNodes
// object), and choose a different node for each request. 
public class AlternatorEndpointProvider implements DynamoDbEndpointProvider {
	AlternatorLiveNodes liveNodes;
	public AlternatorEndpointProvider(URI seedURI) {
		liveNodes = AlternatorLiveNodes.create(seedURI);
	}

	@Override
	public CompletableFuture<Endpoint> resolveEndpoint(DynamoDbEndpointParams endpointParams) {
		Endpoint ret = Endpoint.builder().url(liveNodes.nextAsURI()).build();
		CompletableFuture<Endpoint> f = new CompletableFuture<Endpoint>();
		f.complete(ret);
		return f;
	}

}
