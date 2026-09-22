# Implementation Plan: Conciliación de Pagos

**Branch**: `001-payment-reconciliation` | **Date**: 2026-09-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-payment-reconciliation/spec.md`

## Summary

Microservicio que consume pagos reportados y movimientos del core desde Kafka, los concilia
por referencia de operación comparando importes contra una tolerancia fija de PEN 1.00, y
publica el resultado como conforme o discrepante. Expone un único endpoint REST de consulta
por referencia.

Enfoque técnico: Quarkus 3.x sobre Java 21 en modelo bloqueante con virtual threads,
arquitectura hexagonal con un solo aggregate root (`Reconciliation`), y contract-first en las
dos superficies de integración — OpenAPI 3.0.3 para REST y Avro para Kafka, ninguno derivado
del código. La versión 3.0.3 es deliberada, no un descuido: ningún generador del ecosistema
soporta 3.1 con garantías, y bajo 3.1 se rompe el compartir tipos por `$ref` (ver D-01 en
research.md). El generador es el **`openapi-generator-maven-plugin` de OpenAPITools** con
`jaxrs-spec` y `library=quarkus`: se descarta la extensión de Quarkiverse porque sus dos
generadores están en Preview y Experimental. Idempotencia en dos niveles: índice `UNIQUE` en PostgreSQL como garantía y Redis
como filtro previo.

## Technical Context

**Language/Version**: Java 21 (virtual threads, records, sealed interfaces, pattern matching)

**Primary Dependencies**: Quarkus 3.x — `quarkus-rest`, `quarkus-config-yaml`,
`quarkus-hibernate-orm-panache`, `quarkus-jdbc-postgresql`, `quarkus-redis-client`,
`quarkus-smallrye-reactive-messaging-kafka`, `quarkus-confluent-registry-avro`,
`quarkus-rest-client` (requerido por el serde de Confluent) y
`quarkus-logging-json`. Fuera de Quarkus: MapStruct, Lombok (solo entidades JPA),
`com.fasterxml.uuid:java-uuid-generator`. Plugin Maven: `openapi-generator-maven-plugin`,
**en lugar** de la extensión `quarkus-openapi-generator-server`, descartada por inmadurez
(D-01).

**No hay `avro-maven-plugin`.** La generación Avro la hace el codegen que ya trae
`quarkus-avro`, arrastrado por `quarkus-confluent-registry-avro`, configurado en
`application.yaml` bajo `avro.codegen.*` (ver R-12).

**Dependencias añadidas durante la implementación**, ninguna opcional:

| Dependencia | Por qué |
| --- | --- |
| `quarkus-rest-jackson` | `quarkus-rest` por sí solo no serializa JSON, y el contrato declara `application/json` |
| `quarkus-hibernate-validator` | Lo exige el código generado con `useBeanValidation`, que emite `@Size` y compañía |
| `mockito-core` *(test)* | Para el doble de `RedisDataSource`: `ValueCommands` tiene demasiados métodos para implementarla a mano |
| `awaitility` *(test)* | El consumo de mensajes es asíncrono; sin espera activa los asserts corren antes de tiempo |
| `smallrye-reactive-messaging-in-memory` *(test)* | El `InMemoryConnector` que la constitución XIII nombra |

Nota sobre `quarkus-junit`: el artefacto es ese, no `quarkus-junit5`, que está relocalizado
en 3.31.2 y emite un warning de migración.

**Storage**: PostgreSQL en Supabase (conexión directa o pooler en modo sesión, nunca modo
transacción). Una sola tabla: `reconciliations`. DDL manual en `db/schema.sql`; Hibernate
nunca gestiona el esquema. Redis local para el filtro de idempotencia.

**Testing**: JUnit 5 puro para dominio y mappers, sin `@QuarkusTest` ni contexto CDI.
`InMemoryConnector` de SmallRye para los adaptadores de mensajería. Testcontainers prohibido.

**Target Platform**: JVM, empaquetado JVM (no nativo). Linux/contenedor gestionado por el
equipo.

**Project Type**: Microservicio backend orientado a eventos, con una superficie REST de solo
lectura.

**Performance Goals**: No hay objetivo cuantitativo de latencia ni throughput: es una demo
técnica. El límite real de concurrencia es el pool JDBC, dimensionado explícitamente en
`min-size: 5` / `max-size: 16` (R-13), y la concurrencia de consumo queda acotada por el
número de particiones de cada topic, que fija el equipo.

**Constraints**: Sin outbox — la publicación ocurre tras confirmar la transacción y se acepta
perder el evento (FR-014b). Sin métricas, trazas ni dashboards. Sin autenticación: se delega
al perímetro. Ningún contrato se deriva del código.

**Scale/Scope**: 1 aggregate root, 3 casos de uso, 3 puertos de salida, 4 mappers, 4 esquemas
Avro, 1 endpoint REST, 1 tabla.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Evaluado contra `.specify/memory/constitution.md` v3.0.0.

| Principio | Gate | Estado |
| --- | --- | --- |
| I. No sobreextenderse | Nada fuera del spec: 1 endpoint, 3 casos de uso, sin reintentos ni circuit breakers | **PASS** |
| II. Documentación verificada | Toda propiedad y firma verificada: Context7 por ambas vías, y fuentes oficiales para los 6 puntos que Context7 no cubría (R-08…R-13). Sin pendientes | **PASS** |
| III. Infraestructura del equipo | Sin Flyway, sin docker-compose, sin creación de topics. Dev Services off. DDL manual. README lista los topics | **PASS** |
| IV. Configuración y secretos | Las 15 variables declaradas, `${NOMBRE}` sin default, nombres de topic incluidos. `@ConfigMapping` en `infrastructure`, nunca inyectado en `domain` | **PASS** |
| V. Arquitectura hexagonal | Estructura de paquetes exacta; control de imports de Checkstyle lo hace cumplir en el build | **PASS** |
| VI. Dependency inversion | Los 3 puertos de salida son interfaces en `domain/port/out`; los servicios reciben puertos | **PASS** |
| VII. Modelado táctico DDD | Constructor privado, `start()`/`reconstitute()`, sin setters, `PanacheRepository` sobre entidad JPA separada | **PASS** |
| VIII. Mensajería | Idempotencia por `UNIQUE` sobre `operation_reference`, Redis como filtro. DLQ explícita. Offsets tras procesar. Sin outbox, documentado | **PASS** |
| IX. Concurrencia | Bloqueante con `@RunOnVirtualThread`, sin Mutiny ni Panache reactive, `ReentrantLock` si hiciera falta | **PASS** |
| X. Java 21 moderno | Records para VOs y comandos, `Optional` solo como retorno de puertos, sin Lombok fuera de entidades JPA, `Instant` para tiempo | **PASS** |
| XI. Idioma | Todo el código en inglés. Glosario respetado — ver resolución abajo | **PASS tras corrección** |
| XII. Calidad | Checkstyle `severity=error` con control de imports; Spotless `check` en verify; el build no modifica archivos | **PASS** |
| XIII. Testing | Matriz de transiciones, validación de VOs, igualdad de montos, idempotencia por consumer, mappers ida y vuelta | **PASS** |

### Violación detectada y resuelta

**XI. Idioma — glosario.** El plan de entrada nombraba el movimiento del core como
`BankTransaction` (`RegisterBankTransactionUseCase`, `BankTransactionConsumer`,
`BankTransactionReported.avsc`, canal `bank-transactions-in`, campo `bankAmount`). El glosario
normativo de la constitución XI fija **`CoreTransaction`**, y el spec ya lo usa en sus
entidades clave.

Resuelto a favor de la constitución, que prevalece sobre el plan. Renombrados:

| Plan de entrada | Adoptado |
| --- | --- |
| `RegisterBankTransactionUseCase` | `RegisterCoreTransactionUseCase` |
| `RegisterBankTransactionService` | `RegisterCoreTransactionService` |
| `BankTransactionConsumer` | `CoreTransactionConsumer` |
| `BankTransactionReported.avsc` | `CoreTransactionReported.avsc` |
| canal `bank-transactions-in` | canal `core-transactions-in` |
| campo Avro `bankAmount` | campo Avro `coreAmount` |

### Desviación menor, aceptada

**XII. Calidad — fase de Checkstyle.** La constitución dice "Checkstyle en `mvn verify`"; el
plan lo vincula a la fase `validate`. No es conflicto: `validate` precede a `verify` en el
ciclo de vida de Maven, así que `mvn verify` sigue ejecutándolo, y hacerlo antes falla más
rápido. Se adopta `validate`.

### Renombrado de puerto por coherencia con la clarificación

`ProcessedEventStore` pasa a **`ProcessedOperationStore`**. La clarificación de la sesión
2026-09-20 fijó que la idempotencia se ancla en la referencia de operación y **no** en el
identificador de evento; un puerto llamado "EventStore" cuyo método recibe una
`OperationReference` induciría al error contrario.

## Project Structure

### Documentation (this feature)

```text
specs/001-payment-reconciliation/
├── plan.md              # Este archivo
├── spec.md              # Especificación funcional
├── research.md          # Fase 0 — 13 decisiones verificadas, sin pendientes
├── data-model.md        # Fase 1 — agregado, VOs, esquema de tabla
├── quickstart.md        # Fase 1 — guía de validación end-to-end
├── contracts/           # Fase 1 — OpenAPI y esquemas Avro
│   ├── reconciliation-api.yaml
│   ├── PaymentExecuted.avsc
│   ├── CoreTransactionReported.avsc
│   ├── ReconciliationMatched.avsc
│   └── DiscrepancyDetected.avsc
├── checklists/
│   └── requirements.md
└── tasks.md             # Fase 2 — lo genera /speckit-tasks, no este comando
```

### Source Code (repository root)

```text
db/
└── schema.sql                        # DDL manual, lo ejecuta una persona

src/main/avro/                        # Contratos Avro, fuente de verdad
├── PaymentExecuted.avsc
├── CoreTransactionReported.avsc
├── ReconciliationMatched.avsc
└── DiscrepancyDetected.avsc

src/main/resources/
├── application.yaml
└── openapi/
    └── reconciliation-api.yaml       # Contrato REST, fuente de verdad

src/main/java/com/bancatest/reconciliation/
├── domain/
│   ├── model/
│   │   └── Reconciliation.java       # Único aggregate root
│   ├── model/vo/
│   │   ├── Amount.java
│   │   ├── Currency.java
│   │   ├── OperationReference.java
│   │   ├── AccountNumber.java
│   │   ├── ReconciliationId.java
│   │   └── ReconciliationStatus.java # Enum con su matriz de transiciones
│   ├── port/in/
│   │   ├── ReconcilePaymentUseCase.java
│   │   ├── RegisterCoreTransactionUseCase.java
│   │   └── QueryReconciliationUseCase.java
│   ├── port/out/
│   │   ├── ReconciliationRepository.java
│   │   ├── ProcessedOperationStore.java
│   │   ├── ReconciliationEventPublisher.java
│   │   ├── ToleranceProvider.java        # ver nota abajo
│   │   └── ReconciliationIdProvider.java # ver nota abajo
│   └── exception/
│       ├── InvalidAmountException.java
│       ├── CurrencyMismatchException.java
│       └── IllegalStateTransitionException.java
├── application/service/
│   ├── ReconcilePaymentService.java
│   ├── RegisterCoreTransactionService.java
│   └── QueryReconciliationService.java
└── infrastructure/
    ├── adapter/in/kafka/
    │   ├── PaymentConsumer.java
    │   ├── CoreTransactionConsumer.java
    │   └── mapper/                   # Avro → comandos del dominio
    ├── adapter/in/rest/
    │   ├── ReconciliationResource.java   # implements la interfaz generada
    │   └── mapper/                   # dominio → DTOs generados
    ├── adapter/out/persistence/
    │   ├── entity/ReconciliationEntity.java
    │   ├── mapper/                   # dominio ↔ entidad JPA
    │   └── PanacheReconciliationRepository.java
    ├── adapter/out/redis/
    │   └── RedisProcessedOperationStore.java
    ├── adapter/out/messaging/
    │   ├── KafkaReconciliationEventPublisher.java
    │   └── mapper/                   # dominio → Avro de salida
    └── config/
        └── ReconciliationConfig.java # @ConfigMapping: tolerancia y TTL

src/test/java/com/bancatest/reconciliation/
├── domain/                           # JUnit puro, sin CDI
├── application/
└── infrastructure/                   # InMemoryConnector, sin contenedores

.env.example
```

**Structure Decision**: proyecto Maven único con la estructura hexagonal obligatoria de la
constitución V. No hay separación frontend/backend ni multi-módulo: la frontera que importa
es la de paquetes, y la hace cumplir el control de imports de Checkstyle en el build, no la
disciplina del desarrollador.

Los artefactos generados — clases Avro del codegen de `quarkus-avro` e interfaces JAX-RS y
DTOs de `openapi-generator-maven-plugin` — viven en `target/` y no se versionan. Las fuentes
de verdad son los `.avsc` y el `.yaml`.

## Canales de mensajería

El nombre de canal es interno y estable; el nombre de topic llega por entorno (constitución
IV, v3.1.0) y no aparece en el repositorio.

**Los mensajes salientes se publican con `operationReference` como key**, para que todos los
eventos de una operación caigan en la misma partición. Para los **entrantes**, el spec declara
como dependencia que los productores hacen lo mismo: de ello depende que dos mensajes de una
misma operación se procesen en serie y no concurrentemente (ver *Dependencias* en spec.md).

| Dirección | Canal | Topic desde | DLQ desde |
| --- | --- | --- | --- |
| incoming | `payments-in` | `${KAFKA_TOPIC_PAYMENTS_IN}` | `${KAFKA_TOPIC_PAYMENTS_DLQ}` |
| incoming | `core-transactions-in` | `${KAFKA_TOPIC_CORE_TRANSACTIONS_IN}` | `${KAFKA_TOPIC_CORE_TRANSACTIONS_DLQ}` |
| outgoing | `results-out` | `${KAFKA_TOPIC_RESULTS_OUT}` | — |
| outgoing | `discrepancies-out` | `${KAFKA_TOPIC_DISCREPANCIES_OUT}` | — |

Esqueleto de la configuración, con las propiedades verificadas en R-03, R-06, R-09 y R-10:

```yaml
mp:
  messaging:
    incoming:
      payments-in:
        connector: smallrye-kafka
        topic: ${KAFKA_TOPIC_PAYMENTS_IN}
        enable.auto.commit: false
        failure-strategy: dead-letter-queue
        dead-letter-queue.topic: ${KAFKA_TOPIC_PAYMENTS_DLQ}
        partition.assignment.strategy: >-
          org.apache.kafka.clients.consumer.CooperativeStickyAssignor
    outgoing:
      results-out:
        connector: smallrye-kafka
        topic: ${KAFKA_TOPIC_RESULTS_OUT}
        key.serializer: org.apache.kafka.common.serialization.StringSerializer
        auto.register.schemas: false
```

`core-transactions-in` y `discrepancies-out` replican la forma de su pareja. Ninguna
propiedad lleva valor por defecto: si falta la variable, el arranque falla.

`auto.register.schemas: false` es obligatorio y no es el default del serde de Confluent
(R-06): omitirlo registraría los esquemas desde la aplicación, que es justo lo que la
constitución III prohíbe.

## Flujo de los dos casos de uso de escritura

**ReconcilePaymentService** (pago reportado):

1. Validar el comando: `Amount` válido y `Currency` = PEN. Inválido → excepción → DLQ del
   canal de pagos (FR-022).
2. Filtro Redis: `SET dedup:{operationReference} NX EX ttl`. Si ya existía → descartar como
   éxito, log INFO (FR-019).
3. `Reconciliation.start(...)` → agregado PENDING con su `ReconciliationId` UUIDv7.
4. Persistir. Si el `UNIQUE` rechaza → duplicado que burló el filtro → descartar como éxito.
5. No se publica nada: PENDING no genera evento (FR-015).

**RegisterCoreTransactionService** (movimiento del core):

1. Validar el comando igual que arriba. Inválido → DLQ del canal de movimientos.
2. Buscar la conciliación por `OperationReference`. Vacío → descartar, log WARN (FR-021).
3. **Comprobar el estado antes de transicionar**. Si ya está resuelta → descartar, log WARN,
   sin invocar transición (FR-020, decisión de la clarificación).
4. Calcular la diferencia como resta directa y compararla con la tolerancia.
5. `match(...)` o `markAsMismatched(...)` sobre el agregado.
6. Persistir y **confirmar la transacción**.
7. Publicar el evento en el canal correspondiente, ya fuera de la transacción (FR-014b).

## Complexity Tracking

> Sin violaciones de la constitución pendientes de justificar. La única detectada (XI,
> glosario) se resolvió adoptando el término de la constitución, sin añadir complejidad.

| Violación | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| `quarkus-rest-client` como dependencia | Lo exige `quarkus-confluent-registry-avro`: las librerías del registry de Confluent usan el cliente REST de Jakarta | No hay alternativa: sin él, el serde de Confluent no resuelve sus dependencias. Verificado en la guía de Quarkus (R-06) |
| Versión mínima de `org.postgresql:postgresql` exigida (>= 42.7.9), sobrescribiendo el BOM solo si este resuelve 42.7.8 | La 42.7.8 reintrodujo pinning de virtual threads; el PR #3849 lo corrigió en 42.7.9. JEP 491 —que lo resolvería en el runtime— es de Java 24, no de Java 21 | Fijar una versión anterior a 42.7.8, como se planteó primero, cambiaba un riesgo por otro: renunciar a correcciones posteriores, de seguridad incluidas. Ver R-13 |

## Post-Design Constitution Re-check

Reevaluado tras generar `data-model.md` y `contracts/`:

- **V y VI**: los contratos Avro y OpenAPI viven fuera de `domain`; las clases generadas son
  de infraestructura y ningún tipo generado aparece en una firma del dominio. **PASS**
- **VII**: el modelo de datos confirma constructor privado, factories y ausencia de setters;
  la entidad JPA es una clase distinta con mapper explícito. **PASS**
- **VIII**: los cuatro esquemas llevan `default` en todo campo y el `UNIQUE` está en el DDL.
  **PASS**
- **X**: `Instant` en todo instante técnico (`timestamp-millis` en Avro), `BigDecimal` con
  `logicalType decimal` en toda la cadena, sin `double`. **PASS**
- **XI**: glosario aplicado de forma uniforme a clases, canales, esquemas y columnas.
  **PASS**

Sin nuevas violaciones introducidas por el diseño.
