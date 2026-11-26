package com.github.jsonldjava.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ContextTest {

	private static final String EXTERNAL_CONTEXT = "https://contexts.example/network/tarana.json";

	@Test
	void externalContextUsesCacheWhenGatewayAndContextServerDiffer() {
		String routed = Context.routeRemoteContext(EXTERNAL_CONTEXT, "https://broker.example/",
				"http://localhost:9090/");

		assertEquals(
				"http://localhost:9090/ngsi-ld/v1/jsonldContexts/createcache/"
						+ "https%3A%2F%2Fcontexts.example%2Fnetwork%2Ftarana.json",
				routed);
	}

	@Test
	void externalContextUsesCacheWhenGatewayAndContextServerMatch() {
		String routed = Context.routeRemoteContext(EXTERNAL_CONTEXT, "http://scorpio:9090/",
				"http://scorpio:9090/");

		assertEquals(
				"http://scorpio:9090/ngsi-ld/v1/jsonldContexts/createcache/"
						+ "https%3A%2F%2Fcontexts.example%2Fnetwork%2Ftarana.json",
				routed);
	}

	@Test
	void gatewayHostedContextIsRewrittenToInternalContextServer() {
		String routed = Context.routeRemoteContext(
				"https://broker.example/ngsi-ld/v1/jsonldContexts/urn%3Acontext%3Atarana",
				"https://broker.example/", "http://localhost:9090/");

		assertEquals("http://localhost:9090/ngsi-ld/v1/jsonldContexts/urn%3Acontext%3Atarana", routed);
	}

	@Test
	void internalContextServerUrlIsUnchanged() {
		String contextUrl = "http://localhost:9090/ngsi-ld/v1/jsonldContexts/urn%3Acontext%3Atarana";

		assertEquals(contextUrl,
				Context.routeRemoteContext(contextUrl, "https://broker.example/", "http://localhost:9090/"));
	}
}
