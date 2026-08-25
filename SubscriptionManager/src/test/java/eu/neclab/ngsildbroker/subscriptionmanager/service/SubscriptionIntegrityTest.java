package eu.neclab.ngsildbroker.subscriptionmanager.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Table;
import com.github.jsonldjava.core.Context;
import com.github.jsonldjava.core.JsonLDService;

import eu.neclab.ngsildbroker.commons.datatypes.Subscription;
import eu.neclab.ngsildbroker.commons.datatypes.requests.subscription.SubscriptionRequest;
import eu.neclab.ngsildbroker.commons.exceptions.ResponseException;
import eu.neclab.ngsildbroker.subscriptionmanager.repository.SubscriptionInfoDAO;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple4;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowIterator;
import io.vertx.mutiny.sqlclient.RowSet;

class SubscriptionIntegrityTest {

	@Test
	void validAndOrphanedStoredSubscriptionsCoexistWithoutFailingColdLoad() throws Exception {
		SubscriptionService service = new SubscriptionService();
		service.ldService = mock(JsonLDService.class);
		when(service.ldService.parsePure(any())).thenReturn(Uni.createFrom().item(new Context()));
		Map<String, Object> validPayload = new ObjectMapper().readValue("""
				{
				  "@id": "urn:ngsi-ld:Subscription:valid",
				  "@type": ["https://uri.etsi.org/ngsi-ld/Subscription"],
				  "https://uri.etsi.org/ngsi-ld/entities": [{
				    "@type": ["https://example.test/Device"]
				  }],
				  "https://uri.etsi.org/ngsi-ld/notification": [{
				    "https://uri.etsi.org/ngsi-ld/endpoint": [{
				      "https://uri.etsi.org/ngsi-ld/accept": [{"@value": "application/json"}],
				      "https://uri.etsi.org/ngsi-ld/uri": [{"@value": "http://receiver.test/notify"}]
				    }],
				    "https://uri.etsi.org/ngsi-ld/format": [{"@value": "normalized"}]
				  }]
				}
				""", Map.class);
		Map<String, Object> orphanPayload = Map.of("@id", "urn:ngsi-ld:Subscription:orphan");
		Map<String, Object> contextBody = Map.of("@context", Map.of());

		service.loadStoredSubscriptions(List.of(
				Tuple4.of("tenant-a", validPayload, "urn:valid-context", contextBody),
				Tuple4.of("tenant-a", orphanPayload, "urn:missing", null)))
				.await().indefinitely();

		assertEquals(1, service.getInvalidSubscriptionCount());
		Field tableField = SubscriptionService.class.getDeclaredField("tenant2subscriptionId2Subscription");
		tableField.setAccessible(true);
		@SuppressWarnings("unchecked")
		Table<String, String, SubscriptionRequest> table =
				(Table<String, String, SubscriptionRequest>) tableField.get(service);
		assertEquals("urn:ngsi-ld:Subscription:valid",
				table.get("tenant-a", "urn:ngsi-ld:Subscription:valid").getId());
	}

	@Test
	void retrieveReturnsTypedErrorForMissingContext() {
		SubscriptionService service = new SubscriptionService();
		service.subDAO = mock(SubscriptionInfoDAO.class);
		RowSet<Row> stored = rowSet(subscriptionRow("urn:ngsi-ld:Subscription:orphan"), null, null);
		when(service.subDAO.getSubscription("tenant-a", "urn:ngsi-ld:Subscription:orphan"))
				.thenReturn(Uni.createFrom().item(stored));

		CompletionException thrown = assertThrows(CompletionException.class,
				() -> service.getSubscription("tenant-a", "urn:ngsi-ld:Subscription:orphan").await().indefinitely());
		ResponseException response = (ResponseException) thrown.getCause();

		assertEquals(500, response.getErrorCode());
		assertEquals("urn:norda:scorpio:error:subscription-context-missing", response.getJson().get("type"));
	}

	@Test
	void retrieveReturnsTypedNotLoadedErrorForInvalidStoredContext() {
		String subscriptionId = "urn:ngsi-ld:Subscription:invalid-context";
		SubscriptionService service = new SubscriptionService();
		service.subDAO = mock(SubscriptionInfoDAO.class);
		service.ldService = mock(JsonLDService.class);
		JsonObject context = new JsonObject().put("@context", "not a valid context");
		RowSet<Row> stored = rowSet(subscriptionRow(subscriptionId), context, "urn:context");
		when(service.subDAO.getSubscription("tenant-a", subscriptionId))
				.thenReturn(Uni.createFrom().item(stored));
		when(service.ldService.parsePure(any()))
				.thenReturn(Uni.createFrom().failure(new IllegalArgumentException("invalid context")));

		CompletionException thrown = assertThrows(CompletionException.class,
				() -> service.getSubscription("tenant-a", subscriptionId).await().indefinitely());
		ResponseException response = (ResponseException) thrown.getCause();

		assertEquals(500, response.getErrorCode());
		assertEquals("urn:norda:scorpio:error:subscription-not-loaded", response.getJson().get("type"));
	}

	@SuppressWarnings("unchecked")
	@Test
	void sameSubscriptionIdUsesTenantScopedLoadedStatus() throws Exception {
		String subscriptionId = "urn:ngsi-ld:Subscription:shared";
		SubscriptionService service = new SubscriptionService();
		service.subDAO = mock(SubscriptionInfoDAO.class);
		service.ldService = mock(JsonLDService.class);
		Context parsedContext = mock(Context.class);
		when(parsedContext.serialize()).thenReturn(Map.of("@context", "https://example.test/context.jsonld"));
		when(service.ldService.parsePure(any())).thenReturn(Uni.createFrom().item(parsedContext));
		SubscriptionRequest tenantARequest = loadedRequest("active-a");
		SubscriptionRequest tenantBRequest = loadedRequest("active-b");
		Field tableField = SubscriptionService.class.getDeclaredField("tenant2subscriptionId2Subscription");
		tableField.setAccessible(true);
		Table<String, String, SubscriptionRequest> table =
				(Table<String, String, SubscriptionRequest>) tableField.get(service);
		table.put("tenant-a", subscriptionId, tenantARequest);
		table.put("tenant-b", subscriptionId, tenantBRequest);
		JsonObject context = new JsonObject().put("@context", Map.of("Device", "https://example.test/Device"));
		RowSet<Row> tenantAStored = rowSet(subscriptionRow(subscriptionId), context, "urn:context");
		RowSet<Row> tenantBStored = rowSet(subscriptionRow(subscriptionId), context, "urn:context");
		when(service.subDAO.getSubscription("tenant-a", subscriptionId))
				.thenReturn(Uni.createFrom().item(tenantAStored));
		when(service.subDAO.getSubscription("tenant-b", subscriptionId))
				.thenReturn(Uni.createFrom().item(tenantBStored));

		assertEquals("active-a", service.getSubscription("tenant-a", subscriptionId).await().indefinitely().get("status"));
		assertEquals("active-b", service.getSubscription("tenant-b", subscriptionId).await().indefinitely().get("status"));
		assertEquals("https://example.test/context.jsonld",
				service.getSubscription("tenant-a", subscriptionId).await().indefinitely().get("@context"));

		when(service.subDAO.deleteSubscription(any()))
				.thenReturn(Uni.createFrom().item(mock(RowSet.class)));
		service.deleteSubscription("tenant-a", subscriptionId).await().indefinitely();

		assertNull(table.get("tenant-a", subscriptionId));
		assertEquals(tenantBRequest, table.get("tenant-b", subscriptionId));
		assertEquals("active-b", service.getSubscription("tenant-b", subscriptionId).await().indefinitely().get("status"));
	}

	private static SubscriptionRequest loadedRequest(String status) {
		Subscription subscription = mock(Subscription.class);
		when(subscription.getStatus()).thenReturn(status);
		SubscriptionRequest request = mock(SubscriptionRequest.class);
		when(request.getSubscription()).thenReturn(subscription);
		return request;
	}

	private static JsonObject subscriptionRow(String id) {
		return new JsonObject().put("@id", id);
	}

	@SuppressWarnings("unchecked")
	private static RowSet<Row> rowSet(JsonObject subscription, JsonObject context, String contextId) {
		Row row = mock(Row.class);
		when(row.getJsonObject(0)).thenReturn(subscription);
		when(row.getJsonObject(1)).thenReturn(context);
		when(row.getString(2)).thenReturn(contextId);
		RowIterator<Row> iterator = mock(RowIterator.class);
		when(iterator.next()).thenReturn(row);
		RowSet<Row> rows = mock(RowSet.class);
		when(rows.size()).thenReturn(1);
		when(rows.iterator()).thenReturn(iterator);
		return rows;
	}
}
