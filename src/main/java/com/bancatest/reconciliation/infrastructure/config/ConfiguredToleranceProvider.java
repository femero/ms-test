package com.bancatest.reconciliation.infrastructure.config;

import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.Currency;
import com.bancatest.reconciliation.domain.port.out.ToleranceProvider;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;

/**
 * Converts the configured tolerance into a domain value object.
 *
 * <p>This is the only place that touches both {@code ReconciliationConfig} and the domain,
 * which is precisely why it lives in infrastructure. The figure is read as text and handed
 * straight to {@link BigDecimal}, never through a {@code double}.
 *
 * <p>Resolved once at construction: a malformed tolerance then fails at startup rather than
 * on the first reconciliation that needs it.
 */
@ApplicationScoped
public class ConfiguredToleranceProvider implements ToleranceProvider {

    private final Amount tolerance;

    public ConfiguredToleranceProvider(ReconciliationConfig config) {
        this.tolerance = new Amount(
                new BigDecimal(config.tolerance().amount()),
                Currency.of(config.tolerance().currency()));
    }

    @Override
    public Amount tolerance() {
        return tolerance;
    }
}
