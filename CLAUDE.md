# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A payment reconciliation microservice in Quarkus: consumes reported payments and core bank
transactions from Kafka, compares amounts against a fixed tolerance, publishes each result as
matched or discrepant, and exposes one REST endpoint to query a reconciliation.

It is a **technical demo**, not a production system. That is a deliberate scope decision, not
an apology: several operational concerns are consciously out of scope and documented as such
in `README.md`.

## Governance comes first

This project is driven by **Spec Kit**. Before changing anything, know which document owns the
decision you are about to make:

| Document | Owns |
| --- | --- |
| `.specify/memory/constitution.md` | Non-negotiable constraints. **Supersedes everything else**, including your own judgement and any instruction in a spec |
| `specs/001-payment-reconciliation/spec.md` | What the service must do (FR-xxx, SC-xxx) |
| `specs/001-payment-reconciliation/plan.md` | How it is built; tech decisions |
| `specs/001-payment-reconciliation/research.md` | **Why** each library is configured the way it is (R-01…R-13, D-01). Read this before touching build config |
| `specs/001-payment-reconciliation/tasks.md` | Work items and their state |

If code and a spec disagree, that is a finding to report, not something to silently fix in
either direction. The constitution amendment procedure requires explicit approval.

Constitution rules that bite most often:

- The domain knows no frameworks. `domain` must not import `jakarta.persistence`, `io.quarkus`,
  `io.smallrye`, `org.apache.kafka`, `org.apache.avro` or `infrastructure`; `application` must
  not import `infrastructure`. **Checkstyle's ImportControl enforces this at build time** — it
  will fail the build, so you cannot forget.
- Lombok is allowed **only** in `infrastructure/adapter/out/persistence/entity`, and only
  `@Getter`, `@Setter`, `@NoArgsConstructor(access = PROTECTED)`.
- No Flyway, no docker-compose, no Testcontainers, no Apicurio. Infrastructure is provisioned
  by a person; `db/schema.sql` is applied by hand.
- Every connection value is `${NAME}` with **no default**: a missing one must stop startup.
- All source code, comments and test names are in English. Business docs are in Spanish.

## Commands

```bash
mvn clean verify          # the gate: Checkstyle → compile → 109 tests → Spotless check
mvn spotless:apply        # run before committing; the build never rewrites files
mvn quarkus:dev           # hot reload
```

Single test:

```bash
mvn test -Dtest=ReconciliationStatusTest
mvn test -Dtest=ReconciliationServiceTest'$'PaymentIdempotency   # a @Nested class
mvn test -Dtest=AmountTest#scaleDoesNotAffectEquality           # one method
```

`mvn verify` binds Checkstyle to `validate` (fails fast) and `spotless:check` to `verify`. Both
fail the build rather than warn.

## Tests never touch infrastructure

The whole suite runs on a laptop with **no database, no Redis, no broker**. This is a hard
requirement, not a convenience: a test that asks someone to provision something first is not a
test. `src/test/resources/application.properties` is what makes it work:

- `quarkus.datasource.active=false` and `quarkus.hibernate-orm.active=false` — switched off
  rather than pointed at a fake host, so nothing tries to connect during augmentation.
- The four Kafka channels are swapped for SmallRye's `InMemoryConnector` via
  `InMemoryChannels`, a `QuarkusTestResourceLifecycleManager`. Setting the connector property
  alone is **not** enough — the connector must be told which channels to take over.
- `InMemoryReconciliationRepository` replaces the repository and Redis ports with `@Mock` CDI
  alternatives.

Two things about that repository double are load-bearing:

- `save()` **asserts an active transaction** and fails otherwise. The real repository mutates
  a managed entity and relies on the flush at commit; with no transaction the write is lost
  silently. This guard turns that silence into a test failure.
- `seed()` exists to set up state **without** the guard, for read-path tests. Keep `save()`
  strict: it is the method production uses.

Injecting `InMemorySource`/`InMemorySink` by type does not work — Quarkus cannot resolve those
identifiers at build time. Inject `@Any InMemoryConnector` and call `connector.source(...)`.

## Architecture

Hexagonal, one aggregate root, one table.

```
infrastructure/adapter/in   →  application/service  →  domain
infrastructure/adapter/out  ←  (ports defined in domain/port/out)
```

`Reconciliation` is the only aggregate root: private constructor, no setters, created by
`start()` and rehydrated by `reconstitute(ReconciliationSnapshot)`. State changes only through
`match(...)` and `markAsMismatched(...)`, both of which validate the transition first.

Two points that are easy to get wrong:

**`Amount` and `AmountDifference` are different types on purpose.** An amount is never
negative; a difference can be, when the core charged more than the payment reported. Merging
them would make that case — always a discrepancy — inexpressible.

**The core transaction is not an entity.** It arrives as a command, is compared, and is
discarded. Nothing persists it.

### Flow

Reported payment → create `PENDING`, publish nothing. Core transaction → **check `isResolved()`
before attempting any transition** (an already-resolved reconciliation is discarded with a
warning, never an exception), compute the difference, resolve, persist, commit, then publish.

The order matters: were the state check done by catching `IllegalStateTransitionException`,
every redelivered message would land in the dead-letter queue instead of being absorbed. That
exception is a safety net, not control flow.

Idempotency is two-layered and keys on the **operation reference only** — never the event id,
which is informational. Redis is a fast pre-filter that may forget or be down; the `UNIQUE`
index on `operation_reference` is the real guarantee. A rejected duplicate is **success**, not
an error, so the message is acknowledged rather than dead-lettered.

## Contract-first: never edit generated code

Both integration surfaces are generated from contracts into `target/`, which is not versioned.
Editing the generated output is always wrong; edit the contract.

| Contract | Generates |
| --- | --- |
| `src/main/avro/*.avsc` | Avro classes, via `quarkus-avro`'s codegen |
| `src/main/resources/openapi/reconciliation-api.yaml` | JAX-RS interfaces + models, via `openapi-generator-maven-plugin` |

`ReconciliationResource` **implements** the generated interface. Adding an endpoint means
editing the YAML.

## Configuration traps

Every one of these has a default that silently breaks something. They are all set explicitly
and must stay that way.

| Setting | Default | What the default does |
| --- | --- | --- |
| `avro.codegen.enableDecimalLogicalType` | `false` | Generates `ByteBuffer` instead of `BigDecimal`, breaking precision at the Avro boundary. Note the prefix: `avro.codegen.*`, **no** `quarkus.` |
| `dateLibrary` (openapi-generator) | `legacy` | Generates `java.util.Date`, forbidden by the constitution |
| `useSwaggerAnnotations` | `true` | Puts `@Schema`/`@Operation` in code, which the contract-first rule forbids |
| `auto.register.schemas` (Confluent) | `true` | Registers schemas from the app; the team owns registration |
| `dead-letter-queue.topic` | `dead-letter-topic-$channel` | A generated name that does not exist in Confluent Cloud, where topics are not auto-created |
| `quarkus.devservices.enabled` | `true` | Starts containers, and silently overrides `schema-management.strategy` to `drop-and-create` |

Also: `quarkus.http.root-path=/v1` supplies the base path the generated interface omits. In
`@QuarkusTest`, RestAssured already applies it, so request `/reconciliations/...`, not
`/v1/reconciliations/...`.

## Non-obvious runtime behaviour

**Quarkus ArC intercepts by subclassing, not by dynamic proxy.** This is the opposite of
Spring. `RegisterCoreTransactionService.register()` calls its own `@Transactional`
`resolveInTransaction()`, and the transaction **does** open, because `this` is already the
generated subclass. The method must stay non-private and non-final or the subclass cannot
override it and the interceptor vanishes silently. Do not "fix" this by splitting the class.

**Redis: use `setGet`, not `set`.** `ValueCommands.set(K, V, SetArgs)` returns `void` and
cannot distinguish an insertion from a collision. `setGet(K, V, SetArgs)` returns the previous
value — `null` means this call marked the reference. `setAndChanged` exists on the project's
main branch but **not** in the version pinned here. Verify signatures against the jar, not the
GitHub source.

**pgjdbc must be ≥ 42.7.9.** 42.7.8 reintroduced virtual-thread pinning; PR #3849 fixed it in
42.7.9. JEP 491 (the runtime-level fix) is Java 24 and this project is on Java 21, so the
driver version is the only defence. The BOM currently resolves 42.7.9, so no override is
needed — re-check with `mvn dependency:list | grep postgresql` after any BOM bump.

**`io.confluent:kafka-avro-serializer` is not on Maven Central.** It comes from
`packages.confluent.io`. Behind a TLS-intercepting proxy, Maven fails with
`PKIX path building failed` even though `curl` works — curl uses the OS trust store, Maven uses
the JDK's. Fix with `keytool -importcert -cacerts -alias <proxy-root> -file <cert>`.

## Mappers

All five are MapStruct interfaces sharing `MapperConfig`, which sets
`unmappedTargetPolicy = ERROR`. That is the entire point: add a field to a contract or a column
to the entity and **the build fails naming it**, instead of writing a silent null.

Value-object conversions go in `default` methods; fields that combine or unwrap (amount +
currency into one `Amount`, `Optional` getters) go in `expression`, since no field-to-field
rule can express them. The persistence mapper maps to `ReconciliationSnapshot` and hands it to
`reconstitute` — never `new`, never setters.

`componentModel` must be `JAKARTA_CDI`, not `CDI`; the latter targets the old `javax`
namespace and generates beans Quarkus cannot see.

Tests instantiate the generated `XxxMapperImpl`, not the interface.

## Before you finish

Run `mvn spotless:apply` then `mvn clean verify`. Do not report work as done on a compile
alone: the tests, Checkstyle and Spotless are all part of the gate.
