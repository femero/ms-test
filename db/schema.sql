-- Reconciliation schema. Run this by hand against Supabase.
--
-- There is no Flyway and no automatic migration tool, by decision: Hibernate never
-- creates, validates nor alters this schema (quarkus.hibernate-orm.schema-management
-- .strategy=none). A person owns this file and a person applies it.

CREATE TABLE IF NOT EXISTS reconciliations (
    id                  UUID           NOT NULL,

    -- Business key. The UNIQUE index below is the REAL idempotency guarantee:
    -- Redis is only a fast pre-filter and may be unavailable or expired, but this
    -- constraint always rejects a duplicate. A rejection is treated as success,
    -- never as an error.
    operation_reference VARCHAR(64)    NOT NULL,

    -- Informational: no reconciliation rule reads it. Never log it in full.
    -- 34 is the maximum length of an IBAN; the specification fixes no account length.
    account_number      VARCHAR(34)    NOT NULL,

    -- Amounts are always stored as figure + currency in separate columns, never as a
    -- combined string. NUMERIC(19,2) matches the Avro decimal(19,2) contract and the
    -- scale-2 normalisation the domain applies.
    payment_amount      NUMERIC(19,2)  NOT NULL,
    payment_currency    VARCHAR(3)     NOT NULL,

    -- Null while PENDING. Populated together when the reconciliation resolves.
    core_amount         NUMERIC(19,2)      NULL,
    core_currency       VARCHAR(3)         NULL,

    -- May be negative: the core charged more than the payment reported.
    difference_amount   NUMERIC(19,2)      NULL,
    difference_currency VARCHAR(3)         NULL,

    -- Stored as the enum name, never as its ordinal.
    status              VARCHAR(16)    NOT NULL,

    created_at          TIMESTAMPTZ    NOT NULL,
    resolved_at         TIMESTAMPTZ        NULL,

    CONSTRAINT pk_reconciliations PRIMARY KEY (id),
    CONSTRAINT uq_reconciliations_operation_reference UNIQUE (operation_reference),
    CONSTRAINT ck_reconciliations_status CHECK (status IN ('PENDING', 'MATCHED', 'MISMATCHED')),

    -- A resolved reconciliation always carries its core amount, difference and instant.
    -- Invariant 3 of the aggregate, enforced here too so the database cannot hold a
    -- state the domain considers impossible.
    CONSTRAINT ck_reconciliations_resolved_is_complete CHECK (
        (status = 'PENDING'
            AND core_amount IS NULL
            AND difference_amount IS NULL
            AND resolved_at IS NULL)
        OR
        (status IN ('MATCHED', 'MISMATCHED')
            AND core_amount IS NOT NULL
            AND core_currency IS NOT NULL
            AND difference_amount IS NOT NULL
            AND difference_currency IS NOT NULL
            AND resolved_at IS NOT NULL)
    )
);

-- Supports the only query the service makes: lookup by operation reference, used both
-- by the core-transaction consumer and by the REST endpoint. The UNIQUE constraint
-- above already creates this index, so no separate one is needed.

-- Row Level Security is explicitly disabled: this is a testing database with
-- fictitious data only. Supabase exposes every table in "public" through its
-- auto-generated REST API (Data API), so while RLS is off anyone holding the
-- project's anon key can read, insert or delete rows. The service itself is not
-- affected either way: it connects as the table owner, which bypasses RLS.
-- Before loading real data, switch this to ENABLE ROW LEVEL SECURITY.
ALTER TABLE reconciliations DISABLE ROW LEVEL SECURITY;