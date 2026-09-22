package com.bancatest.reconciliation.infrastructure.adapter.out.redis;

import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.port.out.ProcessedOperationStore;
import com.bancatest.reconciliation.infrastructure.config.ReconciliationConfig;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Fast idempotency pre-filter backed by Redis.
 *
 * <p>Uses {@code setGet}, not {@code set}. Verified against the ValueCommands interface
 * actually shipped in this Quarkus version rather than assumed:
 *
 * <ul>
 *   <li>{@code set(K, V, SetArgs)} returns {@code void}, so it cannot distinguish an
 *       insertion from a collision and would give a filter that silently filters nothing
 *   <li>{@code setnx(K, V)} is conditional but accepts no TTL
 *   <li>{@code setex(K, long, V)} takes a TTL but is unconditional
 *   <li>{@code setGet(K, V, SetArgs)} returns the <em>previous</em> value, which is exactly
 *       the signal needed: {@code null} means the key did not exist, so this call is the one
 *       that marked it
 * </ul>
 *
 * <p>With {@code NX} set, Redis leaves an existing value untouched and still returns it, so
 * one atomic call gives conditionality, expiry and a usable result.
 *
 * <p>This is an optimisation, never the guarantee. When Redis is unavailable the method
 * reports "not seen before" and lets the request through: the unique constraint in the
 * database still rejects the duplicate. Failing open here is deliberate - failing closed
 * would turn a Redis outage into a total outage.
 */
@ApplicationScoped
public class RedisProcessedOperationStore implements ProcessedOperationStore {

    private static final Logger LOG = Logger.getLogger(RedisProcessedOperationStore.class);
    private static final String KEY_PREFIX = "dedup:";
    private static final String MARKER = "1";

    private final ValueCommands<String, String> values;
    private final long ttlSeconds;

    public RedisProcessedOperationStore(RedisDataSource redisDataSource, ReconciliationConfig config) {
        this.values = redisDataSource.value(String.class);
        this.ttlSeconds = config.idempotency().ttl().toSeconds();
    }

    @Override
    public boolean markAsProcessed(OperationReference operationReference) {
        String key = KEY_PREFIX + operationReference.value();
        try {
            String previous = values.setGet(key, MARKER, new SetArgs().nx().ex(ttlSeconds));
            return previous == null;
        } catch (RuntimeException e) {
            LOG.warnf(
                    e,
                    "Idempotency pre-filter unavailable for %s; falling through to the database constraint",
                    operationReference.value());
            return true;
        }
    }
}
