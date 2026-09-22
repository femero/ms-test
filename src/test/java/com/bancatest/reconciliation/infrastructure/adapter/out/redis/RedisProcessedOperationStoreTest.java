package com.bancatest.reconciliation.infrastructure.adapter.out.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.infrastructure.config.ReconciliationConfig;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Behaviour of the idempotency pre-filter, driven entirely against a mocked Redis client.
 *
 * <p>No Redis instance is involved. What is under test is this adapter's interpretation of
 * the client contract, and that is fully expressible with a double: a real server would only
 * re-confirm what Redis already documents.
 *
 * <p>The cases that matter are the two the adapter exists to distinguish - first sighting
 * versus repeat - and the failure mode, which is deliberately fail-open.
 */
class RedisProcessedOperationStoreTest {

    private static final OperationReference OP = OperationReference.of("OP-000123");
    private static final String EXPECTED_KEY = "dedup:OP-000123";

    private ValueCommands<String, String> values;
    private RedisProcessedOperationStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        values = mock(ValueCommands.class);
        RedisDataSource dataSource = mock(RedisDataSource.class);
        when(dataSource.value(String.class)).thenReturn(values);

        store = new RedisProcessedOperationStore(dataSource, config(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("a null previous value means this call marked the reference: first sighting")
    void firstSightingReturnsTrue() {
        when(values.setGet(eq(EXPECTED_KEY), any(), any(SetArgs.class))).thenReturn(null);

        assertTrue(store.markAsProcessed(OP));
    }

    @Test
    @DisplayName("a non-null previous value means the key already existed: duplicate")
    void repeatSightingReturnsFalse() {
        when(values.setGet(eq(EXPECTED_KEY), any(), any(SetArgs.class))).thenReturn("1");

        assertFalse(store.markAsProcessed(OP));
    }

    @Test
    @DisplayName("the key is namespaced by operation reference, never by event id")
    void keyIsDerivedFromOperationReference() {
        when(values.setGet(any(), any(), any(SetArgs.class))).thenReturn(null);

        store.markAsProcessed(OperationReference.of("OP-999"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(values).setGet(key.capture(), any(), any(SetArgs.class));
        assertEquals("dedup:OP-999", key.getValue());
    }

    @Test
    @DisplayName("when Redis is unavailable the filter fails open, leaving the database to decide")
    void failsOpenOnRedisOutage() {
        when(values.setGet(any(), any(), any(SetArgs.class))).thenThrow(new RuntimeException("connection refused"));

        // True, not false: failing closed would turn a Redis outage into a total outage.
        // The unique constraint still rejects any genuine duplicate.
        assertTrue(store.markAsProcessed(OP));
    }

    @Test
    @DisplayName("the configured TTL is passed through to the SET arguments")
    void ttlComesFromConfiguration() {
        when(values.setGet(any(), any(), any(SetArgs.class))).thenReturn(null);

        store.markAsProcessed(OP);

        ArgumentCaptor<SetArgs> args = ArgumentCaptor.forClass(SetArgs.class);
        org.mockito.Mockito.verify(values).setGet(any(), any(), args.capture());
        // SetArgs renders itself as the Redis arguments it will emit.
        String rendered = args.getValue().toArgs().toString();
        assertTrue(rendered.contains("NX"), "the write must be conditional: " + rendered);
        assertTrue(rendered.contains("86400"), "the TTL must be the configured 24h: " + rendered);
    }

    private static ReconciliationConfig config(Duration ttl) {
        return new ReconciliationConfig() {
            @Override
            public Tolerance tolerance() {
                return new Tolerance() {
                    @Override
                    public String amount() {
                        return "1.00";
                    }

                    @Override
                    public String currency() {
                        return "PEN";
                    }
                };
            }

            @Override
            public Idempotency idempotency() {
                return () -> ttl;
            }
        };
    }
}
