package eu.neclab.ngsildbroker.atcontextserver.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;

import eu.neclab.ngsildbroker.commons.exceptions.ResponseException;
import eu.neclab.ngsildbroker.commons.storage.ConnectionManager;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowIterator;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;

class ContextDaoTest {

	private static final Map<String, Object> PAYLOAD = Map.of("@context",
			Map.of("Device", "https://example.test/Device"));

	@Test
	void writesImplicitContextToTenantAndDefaultDatabases() {
		RowSet<Row> sharedInserted = rows();
		RowSet<Row> tenantInserted = rows(stringRow("urn:context"));
		// Regression (2026-08-25 builds): a named tenant's context went to its own database
		// only, so GET /jsonldContexts/{id} (default database) answered 404 and the
		// subscription never notified. A PATCH takes the same path with a new id.
		ConnectionManager connectionManager = mock(ConnectionManager.class);
		when(connectionManager.executeQuery(isNull(), eq(ContextDao.INSERT_SHARED_IMPLICIT_SQL), any(Tuple.class),
				eq(false))).thenReturn(Uni.createFrom().item(sharedInserted));
		when(connectionManager.executeQuery(eq("tenant-a"), eq(ContextDao.INSERT_TENANT_IMPLICIT_SQL),
				any(Tuple.class), eq(true))).thenReturn(Uni.createFrom().item(tenantInserted));
		ContextDao dao = new ContextDao();
		dao.connectionManager = connectionManager;

		RestResponse<Object> response = dao.createContextImpl("tenant-a", PAYLOAD).await().indefinitely();

		assertEquals(200, response.getStatus());
		assertEquals("urn:context", response.getEntity());
		verify(connectionManager).executeQuery(isNull(), eq(ContextDao.INSERT_SHARED_IMPLICIT_SQL), any(Tuple.class),
				eq(false));
		verify(connectionManager).executeQuery(eq("tenant-a"), eq(ContextDao.INSERT_TENANT_IMPLICIT_SQL),
				any(Tuple.class), eq(true));
	}

	@Test
	void sharedAndTenantCopiesGetTheSameId() {
		RowSet<Row> sharedInserted = rows();
		RowSet<Row> tenantInserted = rows(stringRow("urn:x"));
		ConnectionManager connectionManager = mock(ConnectionManager.class);
		Tuple[] shared = new Tuple[1];
		Tuple[] tenant = new Tuple[1];
		when(connectionManager.executeQuery(isNull(), eq(ContextDao.INSERT_SHARED_IMPLICIT_SQL), any(Tuple.class),
				eq(false))).thenAnswer(capture(shared, sharedInserted));
		when(connectionManager.executeQuery(eq("tenant-a"), eq(ContextDao.INSERT_TENANT_IMPLICIT_SQL),
				any(Tuple.class), eq(true))).thenAnswer(capture(tenant, tenantInserted));
		ContextDao dao = new ContextDao();
		dao.connectionManager = connectionManager;

		dao.createContextImpl("tenant-a", PAYLOAD).await().indefinitely();

		assertEquals(shared[0].getString(0), tenant[0].getString(0));
	}

	@Test
	void doesNotCreateTheTenantCopyWhenTheDefaultDatabaseRejectsTheContext() {
		ConnectionManager connectionManager = mock(ConnectionManager.class);
		when(connectionManager.executeQuery(isNull(), eq(ContextDao.INSERT_SHARED_IMPLICIT_SQL), any(Tuple.class),
				eq(false))).thenReturn(Uni.createFrom().failure(new IllegalStateException("database down")));
		ContextDao dao = new ContextDao();
		dao.connectionManager = connectionManager;

		RestResponse<Object> response = dao.createContextImpl("tenant-a", PAYLOAD).await().indefinitely();

		assertEquals(500, response.getStatus());
		verify(connectionManager, never()).executeQuery(eq("tenant-a"), anyString(), any(Tuple.class), eq(true));
	}

	@Test
	void resolvesAContextStoredBeforeTheUpgradeFromTheDefaultDatabase() {
		RowSet<Row> preUpgradeContext = rows(contextRow("urn:old", PAYLOAD));
		// A subscription created before 2026-08-25 keeps a context in the default database
		// only; it must resolve exactly as before, without touching tenant databases.
		ConnectionManager connectionManager = mock(ConnectionManager.class);
		when(connectionManager.executeQuery(isNull(), anyString(), any(Tuple.class), eq(false)))
				.thenReturn(Uni.createFrom().item(preUpgradeContext));
		ContextDao dao = new ContextDao();
		dao.connectionManager = connectionManager;

		Map<String, Object> body = dao.getById("urn:old", false).await().indefinitely();

		assertEquals(PAYLOAD, body);
		verify(connectionManager, never()).executeQuery(isNull(), eq(ContextDao.SELECT_TENANTS_SQL), any(), eq(false));
	}

	@Test
	void resolvesAContextStoredOnlyInATenantDatabaseAndCopiesItToTheDefault() {
		RowSet<Row> noDefaultRow = rows();
		RowSet<Row> tenants = rows(stringRow("tenant-a"), stringRow("tenant-b"));
		RowSet<Row> noRowInTenantA = rows();
		RowSet<Row> rowInTenantB = rows(contextRow("urn:tenant-only", PAYLOAD));
		RowSet<Row> copiedRow = rows();
		// A context written by the 2026-08-25 builds lives in one tenant's database only.
		ConnectionManager connectionManager = mock(ConnectionManager.class);
		when(connectionManager.executeQuery(isNull(), anyString(), any(Tuple.class), eq(false)))
				.thenReturn(Uni.createFrom().item(noDefaultRow));
		when(connectionManager.executeQuery(isNull(), eq(ContextDao.SELECT_TENANTS_SQL), isNull(), eq(false)))
				.thenReturn(Uni.createFrom().item(tenants));
		when(connectionManager.executeQuery(eq("tenant-a"), eq(ContextDao.SELECT_CONTEXT_SQL), any(Tuple.class),
				eq(false))).thenReturn(Uni.createFrom().item(noRowInTenantA));
		when(connectionManager.executeQuery(eq("tenant-b"), eq(ContextDao.SELECT_CONTEXT_SQL), any(Tuple.class),
				eq(false))).thenReturn(Uni.createFrom().item(rowInTenantB));
		Tuple[] copied = new Tuple[1];
		when(connectionManager.executeQuery(isNull(), eq(ContextDao.COPY_TO_SHARED_SQL), any(Tuple.class), eq(false)))
				.thenAnswer(capture(copied, copiedRow));
		ContextDao dao = new ContextDao();
		dao.connectionManager = connectionManager;

		Map<String, Object> body = dao.getById("urn:tenant-only", false).await().indefinitely();

		assertEquals(PAYLOAD, body);
		assertEquals("urn:tenant-only", copied[0].getString(0));
		assertEquals("ImplicitlyCreated", copied[0].getString(2));
	}

	@Test
	void answersNotFoundWhenNoDatabaseHasTheContext() {
		RowSet<Row> noDefaultRow = rows();
		RowSet<Row> tenants = rows(stringRow("tenant-a"));
		ConnectionManager connectionManager = mock(ConnectionManager.class);
		when(connectionManager.executeQuery(isNull(), anyString(), any(Tuple.class), eq(false)))
				.thenReturn(Uni.createFrom().item(noDefaultRow));
		when(connectionManager.executeQuery(isNull(), eq(ContextDao.SELECT_TENANTS_SQL), isNull(), eq(false)))
				.thenReturn(Uni.createFrom().item(tenants));
		when(connectionManager.executeQuery(eq("tenant-a"), eq(ContextDao.SELECT_CONTEXT_SQL), any(Tuple.class),
				eq(false))).thenReturn(Uni.createFrom().failure(new IllegalStateException("tenant database gone")));
		ContextDao dao = new ContextDao();
		dao.connectionManager = connectionManager;

		CompletionException thrown = assertThrows(CompletionException.class,
				() -> dao.getById("urn:nowhere", false).await().indefinitely());

		assertEquals(404, ((ResponseException) thrown.getCause()).getErrorCode());
		verify(connectionManager, never()).executeQuery(isNull(), eq(ContextDao.COPY_TO_SHARED_SQL), any(Tuple.class),
				eq(false));
	}

	private static org.mockito.stubbing.Answer<Uni<RowSet<Row>>> capture(Tuple[] into, RowSet<Row> result) {
		return invocation -> {
			into[0] = invocation.getArgument(2);
			return Uni.createFrom().item(result);
		};
	}

	private static Row stringRow(String value) {
		Row row = mock(Row.class);
		when(row.getString(0)).thenReturn(value);
		return row;
	}

	private static Row contextRow(String id, Map<String, Object> body) {
		Row row = mock(Row.class);
		when(row.getString("id")).thenReturn(id);
		when(row.getString("kind")).thenReturn("ImplicitlyCreated");
		when(row.getJsonObject("body")).thenReturn(new JsonObject(body));
		return row;
	}

	@SuppressWarnings("unchecked")
	private static RowSet<Row> rows(Row... content) {
		RowSet<Row> rows = mock(RowSet.class);
		List<Row> list = List.of(content);
		when(rows.size()).thenReturn(list.size());
		when(rows.iterator()).thenAnswer(invocation -> iterator(list.iterator()));
		when(rows.spliterator()).thenAnswer(invocation -> list.spliterator());
		org.mockito.Mockito.doAnswer(invocation -> {
			Consumer<Row> action = invocation.getArgument(0);
			list.forEach(action);
			return null;
		}).when(rows).forEach(any());
		return rows;
	}

	@SuppressWarnings("unchecked")
	private static RowIterator<Row> iterator(Iterator<Row> delegate) {
		RowIterator<Row> iterator = mock(RowIterator.class);
		when(iterator.hasNext()).thenAnswer(invocation -> delegate.hasNext());
		when(iterator.next()).thenAnswer(invocation -> delegate.next());
		return iterator;
	}
}
