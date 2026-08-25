package eu.neclab.ngsildbroker.atcontextserver.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;

import eu.neclab.ngsildbroker.atcontextserver.service.ContextService;
import eu.neclab.ngsildbroker.commons.constants.AppConstants;
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
				.createImplicitly("tenant-a", "{\"@context\":{\"Device\":\"https://example.test/Device\"}}")
				.await().indefinitely();

		assertEquals(200, response.getStatus());
		verify(contextService).createImplicitly(org.mockito.ArgumentMatchers.eq("tenant-a"), anyMap());
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
