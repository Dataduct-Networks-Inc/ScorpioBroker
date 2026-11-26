package eu.neclab.ngsildbroker.subscriptionmanager.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jsonldjava.core.Context;
import com.github.jsonldjava.core.JsonLDService;

import eu.neclab.ngsildbroker.commons.datatypes.requests.subscription.SubscriptionRequest;
import eu.neclab.ngsildbroker.commons.enums.ErrorType;
import eu.neclab.ngsildbroker.commons.exceptions.ResponseException;
import eu.neclab.ngsildbroker.subscriptionmanager.repository.SubscriptionInfoDAO;
import io.smallrye.mutiny.Uni;

class SubscriptionNotificationIsolationTest {

	private static final String SUB_ID = "urn:ngsi-ld:Subscription:tenant-only-context";

	@Test
	void aNotificationThatCannotBeBuiltIsCountedAndDoesNotFailTheEvent() throws Exception {
		// Regression (2026-08-25 builds): the subscription's stored-context URL answered 404,
		// the notification could not be built, and the failure escaped into the
		// Uni.combine().all() of every subscription matching the change, cancelling their
		// notifications too. It must stay this subscription's failure, and be counted.
		SubscriptionService service = new SubscriptionService();
		service.ldService = mock(JsonLDService.class);
		service.subDAO = mock(SubscriptionInfoDAO.class);
		when(service.ldService.parse(any())).thenReturn(Uni.createFrom()
				.failure(new ResponseException(ErrorType.NotFound, "The context was not found")));
		when(service.subDAO.updateNotificationFailure(eq("tenant-a"), eq(SUB_ID), anyString()))
				.thenReturn(Uni.createFrom().voidItem());
		SubscriptionRequest request = new SubscriptionRequest("tenant-a", subscriptionWithStoredContextUrl(),
				new Context());

		service.sendNotification(request,
				List.of(Map.of("@id", "urn:ngsi-ld:Device:1", "@type", List.of("https://example.test/Device"))))
				.await().indefinitely();

		verify(service.subDAO).updateNotificationFailure(eq("tenant-a"), eq(SUB_ID), anyString());
		assertTrue(request.getSubscription().getNotification().getLastFailedNotification() > 0);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> subscriptionWithStoredContextUrl() throws Exception {
		return new ObjectMapper().readValue("""
				{
				  "@id": "%s",
				  "@type": ["https://uri.etsi.org/ngsi-ld/Subscription"],
				  "https://uri.etsi.org/ngsi-ld/entities": [{
				    "@type": ["https://example.test/Device"]
				  }],
				  "https://uri.etsi.org/ngsi-ld/default-context/jsonldContext": [{
				    "@value": "http://localhost:9090/ngsi-ld/v1/jsonldContexts/urn:tenant-only"
				  }],
				  "https://uri.etsi.org/ngsi-ld/notification": [{
				    "https://uri.etsi.org/ngsi-ld/endpoint": [{
				      "https://uri.etsi.org/ngsi-ld/accept": [{"@value": "application/json"}],
				      "https://uri.etsi.org/ngsi-ld/uri": [{"@value": "http://receiver.test/notify"}]
				    }],
				    "https://uri.etsi.org/ngsi-ld/format": [{"@value": "normalized"}]
				  }]
				}
				""".formatted(SUB_ID), Map.class);
	}
}
