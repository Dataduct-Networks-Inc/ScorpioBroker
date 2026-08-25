package eu.neclab.ngsildbroker.atcontextserver.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;

import eu.neclab.ngsildbroker.commons.storage.ConnectionManager;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowIterator;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;

class ContextDaoTest {

	@SuppressWarnings("unchecked")
	@Test
	void writesImplicitContextToRequestedTenantDatabase() {
		ConnectionManager connectionManager = mock(ConnectionManager.class);
		RowSet<Row> rows = mock(RowSet.class);
		RowIterator<Row> iterator = mock(RowIterator.class);
		Row row = mock(Row.class);
		when(rows.size()).thenReturn(1);
		when(rows.iterator()).thenReturn(iterator);
		when(iterator.next()).thenReturn(row);
		when(row.getString(0)).thenReturn("urn:context");
		when(connectionManager.executeQuery(eq("tenant-a"), anyString(), any(Tuple.class), eq(true)))
				.thenReturn(Uni.createFrom().item(rows));
		ContextDao dao = new ContextDao();
		dao.connectionManager = connectionManager;

		RestResponse<Object> response = dao
				.createContextImpl("tenant-a", Map.of("@context", Map.of("Device", "https://example.test/Device")))
				.await().indefinitely();

		assertEquals(200, response.getStatus());
		assertEquals("urn:context", response.getEntity());
		verify(connectionManager).executeQuery(eq("tenant-a"), anyString(), any(Tuple.class), eq(true));
	}
}
