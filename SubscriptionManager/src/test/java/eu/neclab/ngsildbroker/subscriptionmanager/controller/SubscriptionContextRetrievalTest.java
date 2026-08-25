package eu.neclab.ngsildbroker.subscriptionmanager.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import eu.neclab.ngsildbroker.commons.constants.AppConstants;
import eu.neclab.ngsildbroker.commons.constants.NGSIConstants;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.ws.rs.core.HttpHeaders;

@QuarkusTest
@TestProfile(CustomProfile.class)
class SubscriptionContextRetrievalTest {
	private static HttpServer contextServer;

	@BeforeAll
	static void startContextServer() throws IOException {
		contextServer = HttpServer.create(new InetSocketAddress("localhost", 9090), 0);
		contextServer.createContext("/ngsi-ld/v1/jsonldContexts/createimplicitly", exchange -> {
			byte[] response = ")$%^&".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/plain");
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		contextServer.start();
	}

	@AfterAll
	static void stopContextServer() {
		if (contextServer != null) {
			contextServer.stop(0);
		}
	}

	@Test
	void createRetrieveAndDeleteReturnsValidatedStoredContext() {
		String subscriptionId = "urn:ngsi-ld:Subscription:norda:stored-context-regression";
		String payload = """
				{
				  "id": "%s",
				  "type": "Subscription",
				  "entities": [{"type": "https://example.test/ContextGetCanary"}],
				  "notification": {
				    "format": "normalized",
				    "endpoint": {
				      "uri": "http://context-events.test/notify",
				      "accept": "application/json"
				    }
				  }
				}
				""".formatted(subscriptionId);

		Response created = RestAssured.given().body(payload)
				.header(HttpHeaders.CONTENT_TYPE, AppConstants.NGB_APPLICATION_JSON)
				.header(HttpHeaders.ACCEPT, AppConstants.NGB_APPLICATION_JSONLD)
				.post("/ngsi-ld/v1/subscriptions");
		assertEquals(201, created.statusCode(), created.asString());

		try {
			Response retrieved = RestAssured.given()
					.header(HttpHeaders.ACCEPT, AppConstants.NGB_APPLICATION_JSONLD)
					.get("/ngsi-ld/v1/subscriptions/{id}", subscriptionId);
			assertEquals(200, retrieved.statusCode(), retrieved.asString());
			assertEquals(subscriptionId, retrieved.jsonPath().getString("id"));
			Map<String, Object> responseContext = retrieved.jsonPath().getMap(NGSIConstants.JSON_LD_CONTEXT);
			assertNotNull(responseContext);
			assertEquals("https://uri.etsi.org/ngsi-ld/Subscription",
					((Map<?, ?>) responseContext.get("Subscription")).get(NGSIConstants.JSON_LD_ID));
		} finally {
			RestAssured.given().delete("/ngsi-ld/v1/subscriptions/{id}", subscriptionId);
		}
	}
}
