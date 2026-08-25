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
import eu.neclab.ngsildbroker.commons.constants.NGSIConstants;
import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;

class ContextControllerTest {

	private static HttpServerRequest requestWithTenant(String tenant) {
		MultiMap headers = MultiMap.caseInsensitiveMultiMap();
		if (tenant != null) {
			headers.add(NGSIConstants.TENANT_HEADER, tenant);
		}
		HttpServerRequest request = mock(HttpServerRequest.class);
		when(request.headers()).thenReturn(headers);
		return request;
	}

	@Test
	void forwardsTenantForImplicitContextCreation() {
		ContextService contextService = mock(ContextService.class);
		ContextController controller = new ContextController();
		controller.contextService = contextService;
		when(contextService.createImplicitly(org.mockito.ArgumentMatchers.eq("tenant-a"), anyMap()))
				.thenReturn(Uni.createFrom().item(RestResponse.ok("urn:context")));

		RestResponse<Object> response = controller
				.createImplicitly(requestWithTenant("tenant-a"), "{\"@context\":{\"Device\":\"https://example.test/Device\"}}")
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

		controller.createImplicitly(requestWithTenant(null), "{\"@context\":{\"Device\":\"https://example.test/Device\"}}")
				.await().indefinitely();

		verify(contextService).createImplicitly(org.mockito.ArgumentMatchers.eq(AppConstants.INTERNAL_NULL_KEY), anyMap());
	}
}
