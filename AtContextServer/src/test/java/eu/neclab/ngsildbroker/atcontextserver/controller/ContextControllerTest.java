package eu.neclab.ngsildbroker.atcontextserver.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import eu.neclab.ngsildbroker.atcontextserver.service.ContextService;
import eu.neclab.ngsildbroker.commons.constants.AppConstants;
import eu.neclab.ngsildbroker.commons.constants.NGSIConstants;
import io.smallrye.mutiny.Uni;

class ContextControllerTest {

	@Test
	void forwardsTenantForImplicitContextCreation() {
		ContextService contextService = mock(ContextService.class);
		ContextController controller = new ContextController();
		controller.contextService = contextService;
		when(contextService.createImplicitly(org.mockito.ArgumentMatchers.eq("tenant-a"), anyMap()))
				.thenReturn(Uni.createFrom().item(RestResponse.ok("urn:context")));

		RestResponse<Object> response = controller
				.createImplicitly("tenant-a",
						"{\"@context\":{\"Device\":\"https://example.test/Device\"},"
								+ "\"originalAtContext\":[\"https://example.test/context.jsonld\"]}")
				.await().indefinitely();

		assertEquals(200, response.getStatus());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
		verify(contextService).createImplicitly(org.mockito.ArgumentMatchers.eq("tenant-a"), payloadCaptor.capture());
		assertEquals(List.of("https://example.test/context.jsonld"),
				payloadCaptor.getValue().get(NGSIConstants.ORIGINAL_AT_CONTEXT));
	}

	@Test
	void normalizesMissingTenantToMainDatabaseKey() {
		ContextService contextService = mock(ContextService.class);
		ContextController controller = new ContextController();
		controller.contextService = contextService;
		when(contextService.createImplicitly(org.mockito.ArgumentMatchers.eq(AppConstants.INTERNAL_NULL_KEY), anyMap()))
				.thenReturn(Uni.createFrom().item(RestResponse.ok("urn:context")));

		controller.createImplicitly(null, "{\"@context\":{\"Device\":\"https://example.test/Device\"}}")
				.await().indefinitely();

		verify(contextService).createImplicitly(org.mockito.ArgumentMatchers.eq(AppConstants.INTERNAL_NULL_KEY), anyMap());
	}
}
