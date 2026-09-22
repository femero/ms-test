package com.bancatest.reconciliation.infrastructure.adapter.out.persistence.entity;

import com.bancatest.reconciliation.domain.model.vo.ReconciliationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA representation of a reconciliation row.
 *
 * <p>A domain entity is never a JPA entity. This class exists only to talk to the database
 * and is translated to and from {@code Reconciliation} by an explicit mapper. It carries no
 * business rules: it is a row, not an aggregate.
 *
 * <p>This is the only package where Lombok is permitted, because Hibernate requires
 * accessors and a no-argument constructor. Only {@code @Getter}, {@code @Setter} and
 * {@code @NoArgsConstructor(PROTECTED)} are allowed; {@code @Data}, {@code @Builder} and
 * {@code @EqualsAndHashCode} are forbidden everywhere. Checkstyle's import control enforces
 * this boundary at build time.
 */
@Entity
@Table(name = "reconciliations")
@Getter
@Setter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ReconciliationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "operation_reference", nullable = false, unique = true, length = 64)
    private String operationReference;

    @Column(name = "account_number", nullable = false, length = 34)
    private String accountNumber;

    @Column(name = "payment_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paymentAmount;

    @Column(name = "payment_currency", nullable = false, length = 3)
    private String paymentCurrency;

    @Column(name = "core_amount", precision = 19, scale = 2)
    private BigDecimal coreAmount;

    @Column(name = "core_currency", length = 3)
    private String coreCurrency;

    @Column(name = "difference_amount", precision = 19, scale = 2)
    private BigDecimal differenceAmount;

    @Column(name = "difference_currency", length = 3)
    private String differenceCurrency;

    /** Stored by name. An ordinal would reinterpret every stored row if constants moved. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReconciliationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * Creates an empty row for the mapper to populate.
     *
     * <p>Needed because the no-argument constructor is protected, as Hibernate requires and
     * the constitution permits, which leaves it unreachable from the mapper package. This is
     * plain Java rather than another Lombok annotation, so the allowed set stays as it is.
     */
    public static ReconciliationEntity empty() {
        return new ReconciliationEntity();
    }
}
