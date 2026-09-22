package com.bancatest.reconciliation.infrastructure;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import java.util.HashMap;
import java.util.Map;

/**
 * Swaps the four Kafka channels for SmallRye's in-memory connector.
 *
 * <p>Declaring {@code connector=smallrye-in-memory} in a properties file is not enough on its
 * own: the {@code InMemorySource} and {@code InMemorySink} beans only become injectable once
 * the connector has been told which channels to take over, which is what this lifecycle
 * manager does.
 *
 * <p>With this in place the adapter tests exercise the real consumers, mappers, use cases and
 * publisher while nothing leaves the JVM - no broker, no schema registry.
 */
public class InMemoryChannels implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        Map<String, String> config = new HashMap<>();
        config.putAll(InMemoryConnector.switchIncomingChannelsToInMemory("payments-in"));
        config.putAll(InMemoryConnector.switchIncomingChannelsToInMemory("core-transactions-in"));
        config.putAll(InMemoryConnector.switchOutgoingChannelsToInMemory("results-out"));
        config.putAll(InMemoryConnector.switchOutgoingChannelsToInMemory("discrepancies-out"));
        return config;
    }

    @Override
    public void stop() {
        InMemoryConnector.clear();
    }
}
