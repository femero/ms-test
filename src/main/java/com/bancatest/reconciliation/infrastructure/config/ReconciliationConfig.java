package com.bancatest.reconciliation.infrastructure.config;

import io.smallrye.config.ConfigMapping;
import java.time.Duration;

/**
 * Business configuration, bound from {@code application.yaml}.
 *
 * <p>Lives in infrastructure and is never injected into the domain. Its values reach the
 * domain already converted into Value Objects, through a port or as a use-case argument, so
 * the domain never learns that a configuration framework exists.
 *
 * <p>No property declares a default. A missing value must stop the application at startup
 * rather than let it run with a tolerance nobody chose.
 */
@ConfigMapping(prefix = "reconciliation")
public interface ReconciliationConfig {

    Tolerance tolerance();

    Idempotency idempotency();

    /**
     * The fixed amount below which a positive difference is still acceptable.
     *
     * <p>Expressed as an amount with a currency, never as a percentage: the comparison is a
     * direct subtraction and no division happens anywhere in the calculation.
     *
     * <p>The figure is a String so it can be handed to {@code BigDecimal} without ever
     * passing through a {@code double}.
     */
    interface Tolerance {
        String amount();

        String currency();
    }

    interface Idempotency {
        /** How long the Redis pre-filter remembers an operation reference. */
        Duration ttl();
    }
}
