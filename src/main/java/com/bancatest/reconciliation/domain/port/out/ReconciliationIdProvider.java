package com.bancatest.reconciliation.domain.port.out;

import com.bancatest.reconciliation.domain.model.vo.ReconciliationId;

/**
 * Outbound port supplying new reconciliation identities.
 *
 * <p>A port rather than a static call so the domain never learns which UUID version is used
 * or which library produces it, and so a test can supply deterministic identities.
 */
public interface ReconciliationIdProvider {

    ReconciliationId nextId();
}
