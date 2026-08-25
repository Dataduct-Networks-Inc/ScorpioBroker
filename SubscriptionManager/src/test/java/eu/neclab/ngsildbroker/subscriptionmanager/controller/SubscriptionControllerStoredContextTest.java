package eu.neclab.ngsildbroker.subscriptionmanager.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import eu.neclab.ngsildbroker.commons.constants.NGSIConstants;

class SubscriptionControllerStoredContextTest {

	@Test
	void storedContextIsRemovedFromExpandedSubscriptionForSafeCompaction() {
		Map<String, Object> storedContext = Map.of("Device", "https://example.test/Device");
		Map<String, Object> subscription = new HashMap<>();
		subscription.put(NGSIConstants.JSON_LD_ID, "urn:ngsi-ld:Subscription:stored-context");
		subscription.put(NGSIConstants.JSON_LD_CONTEXT, List.of(storedContext));

		Object responseContext = SubscriptionController.removeStoredContext(subscription);

		assertEquals(List.of(storedContext), responseContext);
		assertFalse(subscription.containsKey(NGSIConstants.JSON_LD_CONTEXT));
	}

	@Test
	void declaredContextUrlsExcludeResolvedInlineDefinitions() {
		assertEquals(List.of("https://example.test/context.jsonld"),
				SubscriptionController.getDeclaredContexts(
						List.of("https://example.test/context.jsonld", Map.of("Device", "https://example.test/Device"))));
		assertEquals(List.of(), SubscriptionController.getDeclaredContexts(null));
	}
}
