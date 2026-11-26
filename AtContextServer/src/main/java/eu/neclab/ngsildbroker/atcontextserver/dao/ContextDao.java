package eu.neclab.ngsildbroker.atcontextserver.dao;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import eu.neclab.ngsildbroker.commons.constants.NGSIConstants;
import eu.neclab.ngsildbroker.commons.tools.MicroServiceUtils;
import eu.neclab.ngsildbroker.commons.tools.SerializationTools;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.resteasy.reactive.RestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.neclab.ngsildbroker.commons.constants.AppConstants;
import eu.neclab.ngsildbroker.commons.enums.ErrorType;
import eu.neclab.ngsildbroker.commons.exceptions.ResponseException;
import eu.neclab.ngsildbroker.commons.storage.ConnectionManager;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgException;

@ApplicationScoped
@Startup
public class ContextDao {
	@Inject
	ConnectionManager connectionManager;

	@Inject
	MicroServiceUtils microServiceUtils;

	private static Logger logger = LoggerFactory.getLogger(ContextDao.class);

	String atContextUrl;

	@PostConstruct
void startup() {
		atContextUrl = microServiceUtils.getGatewayString() + NGSIConstants.JSONLD_CONTEXTS;
	}

	/**
	 * A context is addressed by its URL alone ({@code GET /ngsi-ld/v1/jsonldContexts/{id}}),
	 * and nothing that resolves that URL (the JSON-LD loader at notification time, any
	 * client) sends a tenant. So every implicitly created context must also live in the
	 * default database. The id is a hash of the content, so the same id never names two
	 * bodies and the shared insert can ignore an existing row.
	 */
	static final String INSERT_SHARED_IMPLICIT_SQL = "INSERT INTO public.contexts (id, body, kind) values($1, $2, 'ImplicitlyCreated') ON CONFLICT (id) DO NOTHING";

	static final String INSERT_TENANT_IMPLICIT_SQL = "INSERT INTO public.contexts (id, body, kind) values($1, $2, 'ImplicitlyCreated') returning id";

	static final String SELECT_TENANTS_SQL = "SELECT tenant_id FROM tenant ORDER BY tenant_id";

	static final String SELECT_CONTEXT_SQL = "SELECT * FROM contexts WHERE id=$1";

	static final String COPY_TO_SHARED_SQL = "INSERT INTO public.contexts (id, body, kind, createdat) values($1, $2, $3, $4) ON CONFLICT (id) DO NOTHING";

	public Uni<Map<String, Object>> getById(String id, Boolean details) {
		String sql = """
				with a as(select * from contexts WHERE id=$1),
				b as (update contexts set lastusage = now(), numberofhits = numberofhits+1 WHERE id=$1)
				select * from a""";

		return connectionManager.executeQuery(null, sql, Tuple.of(id), false).onItem().transformToUni(rows -> {
			if (rows.size() > 0) {
				return Uni.createFrom().item(toResult(rows.iterator().next(), details));
			}
			return findInTenantDatabases(id).onItem().transformToUni(row -> {
				if (row == null) {
					return Uni.createFrom()
							.failure(new ResponseException(ErrorType.NotFound, "The context was not found"));
				}
				return Uni.createFrom().item(toResult(row, details));
			});
		});

	}

	/**
	 * The 2026-08-25 builds stored implicitly created contexts only in their tenant's
	 * database, so their URLs answered 404 and their subscriptions never notified. Find
	 * such a context there and copy it to the default database, so its URL resolves from
	 * then on. Only a miss in the default database pays for this search.
	 */
	Uni<Row> findInTenantDatabases(String id) {
		return connectionManager.executeQuery(null, SELECT_TENANTS_SQL, null, false).onItem().transformToUni(tenants -> {
			Uni<Row> found = Uni.createFrom().nullItem();
			for (Row tenantRow : tenants) {
				String tenant = tenantRow.getString(0);
				found = found.onItem().ifNull().switchTo(() -> connectionManager
						.executeQuery(tenant, SELECT_CONTEXT_SQL, Tuple.of(id), false).onItem()
						.transform(rows -> rows.size() > 0 ? rows.iterator().next() : (Row) null).onFailure()
						.recoverWithItem(failure -> {
							logger.warn("context lookup in tenant " + tenant + " failed", failure);
							return null;
						}));
			}
			return found.onItem().ifNotNull().call(row -> connectionManager
					.executeQuery(null, COPY_TO_SHARED_SQL,
							Tuple.of(id, row.getJsonObject(NGSIConstants.BODY), row.getString(NGSIConstants.KIND),
									row.getLocalDateTime(NGSIConstants.CREATEDAT.toLowerCase())),
							false)
					.onFailure().invoke(failure -> logger.warn("could not copy context " + id + " to the default database",
							failure))
					.onFailure().recoverWithNull());
		});
	}

	private Map<String, Object> toResult(Row row, boolean details) {
		if (!details) {
			return row.getJsonObject(NGSIConstants.BODY).getMap();
		}
		Map<String, Object> result = new HashMap<>();
		String localId = row.getString(NGSIConstants.ID);
		result.put(NGSIConstants.LOCAL_ID, localId);
		result.put(NGSIConstants.KIND, row.getString(NGSIConstants.KIND));
		result.put(NGSIConstants.NUMBER_OF_HITS, row.getLong(NGSIConstants.NUMBER_OF_HITS.toLowerCase()));
		LocalDateTime lastUsage = row.getLocalDateTime(NGSIConstants.LAST_USAGE.toLowerCase());
		LocalDateTime createdAt = row.getLocalDateTime(NGSIConstants.CREATEDAT.toLowerCase());
		if (lastUsage != null) {
			result.put(NGSIConstants.LAST_USAGE, SerializationTools.formatter.format(lastUsage));
		}
		result.put(NGSIConstants.URL, atContextUrl + URLEncoder.encode(localId, StandardCharsets.UTF_8));
		result.put(NGSIConstants.BODY, row.getJsonObject(NGSIConstants.BODY).getMap());
		if (createdAt != null) {
			result.put(NGSIConstants.CREATEDAT, SerializationTools.formatter.format(createdAt));
		}
		return result;
	}

	public Uni<String> hostContext(Map<String, Object> payload) {
		String sql = "INSERT INTO contexts (id, body, kind) values($1, $2, 'Hosted') returning id";
		String id = "urn:" + UUID.randomUUID();

		return connectionManager.executeQuery(null, sql, Tuple.of(id, new JsonObject(payload)), false)
				.onFailure()
				.recoverWithUni(e -> {

					if (e instanceof PgException pge) {

						MicroServiceUtils.logPGE(pge, logger);
						if (pge.getSqlState().equals(AppConstants.SQL_NOT_FOUND)) {
							return Uni.createFrom()
									.failure(new ResponseException(ErrorType.NotFound, id + " not found"));
						}
						if (pge.getSqlState().startsWith("SB")) {
							return Uni.createFrom().failure(
									new ResponseException(ErrorType.BadRequestData, pge.getErrorMessage()));
						}
					}
					logger.debug("database exception", e);
					return Uni.createFrom().failure(e);
				}).onItem().transformToUni(rows -> {
					if (rows.size() > 0) {
						return Uni.createFrom().item(rows.iterator().next().getString(0));
					} else {
						return Uni.createFrom().failure(
								new ResponseException(ErrorType.InternalError, "An unexcpected error happened"));
					}
				});

	}

	public Uni<Void> deleteById(String id) {
		String sql = "DELETE FROM contexts WHERE id=$1 RETURNING id";

		return connectionManager.executeQuery(null, sql, Tuple.of(id), false).onItem().transformToUni(rows -> {
			if (rows.size() > 0) {
				return Uni.createFrom().voidItem();
			} else {
				return Uni.createFrom()
						.failure(new ResponseException(ErrorType.NotFound, "@Context was not found"));
			}
		});

	}

	public Uni<List<Object>> getAllContexts(String kind, Boolean details) {
		StringBuilder sql = new StringBuilder();
		sql.append("Select * from contexts ");
		if (kind != null) {
			sql.append("where kind='%s'".formatted(kind));
		}
		List<Object> contexts = new ArrayList<>();

		return connectionManager.executeQuery(null, sql.toString(), null, false).onItem().transform(rows -> {
			rows.forEach(row -> {
				Map<String, Object> result = new HashMap<>();

				if (details) {
					result.put(NGSIConstants.LOCAL_ID, row.getString(NGSIConstants.ID));
					result.put(NGSIConstants.NUMBER_OF_HITS,
							row.getLong(NGSIConstants.NUMBER_OF_HITS.toLowerCase()));
					LocalDateTime lastUsage = row.getLocalDateTime(NGSIConstants.LAST_USAGE.toLowerCase());

					if (lastUsage != null) {
						result.put(NGSIConstants.LAST_USAGE, SerializationTools.formatter.format(lastUsage));
					}

					result.put(NGSIConstants.KIND, row.getString(NGSIConstants.KIND));
					result.put(NGSIConstants.BODY, row.getJsonObject(NGSIConstants.BODY).getMap());
					result.put(NGSIConstants.CREATEDAT, SerializationTools.formatter
							.format(row.getLocalDateTime(NGSIConstants.CREATEDAT.toLowerCase())));
					result.put(NGSIConstants.URL, atContextUrl
							+ URLEncoder.encode(row.getString(NGSIConstants.ID), StandardCharsets.UTF_8));
					contexts.add(result);
				} else {
					contexts.add(atContextUrl
							+ URLEncoder.encode(row.getString(NGSIConstants.ID), StandardCharsets.UTF_8));
				}

			});
			return contexts;
		});

	}

	public Uni<RestResponse<Object>> createContextImpl(String tenant, Map<String, Object> payload) {
		java.security.MessageDigest md;
		try {
			md = java.security.MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
		byte[] array = md.digest(payload.toString().getBytes());
		StringBuilder sb = new StringBuilder();
		for (byte b : array) {
			sb.append(Integer.toHexString((b & 0xFF) | 0x100), 1, 3);
		}
		String id = "urn:" + sb;
		JsonObject body = new JsonObject(payload);
		// Shared copy first, so the URL resolves before a subscription can point at it; then
		// the tenant copy, which the subscriptions_context_fkey constraint needs.
		return connectionManager.executeQuery(null, INSERT_SHARED_IMPLICIT_SQL, Tuple.of(id, body), false)
				.onItemOrFailure().transformToUni((sharedRows, sharedFailure) -> {
					if (sharedFailure != null) {
						logger.error("could not store context " + id + " in the default database", sharedFailure);
						return Uni.createFrom().item(RestResponse.<Object>status(
								RestResponse.Status.INTERNAL_SERVER_ERROR, sharedFailure.getMessage()));
					}
					return insertTenantCopy(tenant, id, body);
				});

	}

	private Uni<RestResponse<Object>> insertTenantCopy(String tenant, String id, JsonObject body) {
		return connectionManager.executeQuery(tenant, INSERT_TENANT_IMPLICIT_SQL, Tuple.of(id, body), true).onItemOrFailure()
				.transform((rows, failure) -> {
					if (failure != null) {
						if (failure instanceof PgException
								&& ((PgException) failure).getSqlState().equals("23505")) {// already exists
							return RestResponse.ok(id);
						} else {
							return RestResponse.status(RestResponse.Status.INTERNAL_SERVER_ERROR,
									failure.getMessage());
						}

					}
					if (rows.size() > 0) {
						return RestResponse.ok(rows.iterator().next().getString(0));
					} else {
						return RestResponse.status(RestResponse.Status.INTERNAL_SERVER_ERROR);
					}
				});

	}
}
