# FYI

Conciliación: contrastar dos registros independientes del mismo hecho financiero, 
producidos por partes distintas, para confirmar que coinciden.

Dónde está cada cosa en el código.

## Arquitectura

**Hexagonal (Ports & Adapters).** Las dependencias apuntan siempre hacia el dominio.

```
infrastructure/adapter/in   →  application/service  →  domain
infrastructure/adapter/out  ←  (puertos en domain/port/out)
```

La frontera la impone `import-control.xml` en el build, no la convención.

## Principios SOLID

| | Dónde |
| --- | --- |
| **S** — Responsabilidad única | `domain/model/Reconciliation.java` (solo invariantes) · los tres `application/service/*Service.java`, un caso de uso cada uno |
| **O** — Abierto/cerrado | `domain/port/out/ProcessedOperationStore.java` · dos implementaciones: `adapter/out/redis/RedisProcessedOperationStore.java` y el doble de test |
| **L** — Sustitución de Liskov | `src/test/.../InMemoryReconciliationRepository.java` sustituye a `adapter/out/persistence/PanacheReconciliationRepository.java` |
| **I** — Segregación de interfaces | `domain/port/out/` — cinco puertos de 1 o 2 métodos |
| **D** — Inversión de dependencias | `domain/port/out/ReconciliationRepository.java` (interfaz) ← `adapter/out/persistence/PanacheReconciliationRepository.java` (implementación) |

## Patrones de diseño

| Patrón | Dónde |
| --- | --- |
| Ports & Adapters | Toda la estructura de paquetes |
| Aggregate Root | `domain/model/Reconciliation.java` |
| Value Object | `domain/model/vo/` — `Amount`, `AmountDifference`, `Currency`, `OperationReference`, `AccountNumber`, `ReconciliationId` |
| Repository | `domain/port/out/ReconciliationRepository.java` + `adapter/out/persistence/PanacheReconciliationRepository.java` |
| Factory Method | `Reconciliation.start()` / `reconstitute()` · `Amount.of()` |
| State (tabular) | `domain/model/vo/ReconciliationStatus.java` |
| Strategy | `domain/port/out/ToleranceProvider.java` |
| Parameter Object | `domain/model/ReconciliationSnapshot.java` |
| Adapter | Todo `infrastructure/adapter/` |
| Message Translator | Los cinco mappers MapStruct en `*/mapper/` |
| Idempotent Consumer | `application/service/ReconcilePaymentService.java` + `UNIQUE` en `db/schema.sql` |
| Dead Letter Channel | `application.yaml` → `failure-strategy: dead-letter-queue` |
| Correlation Identifier | `domain/model/vo/OperationReference.java` |

## Integración con Redis

| | |
| --- | --- |
| Adaptador | `infrastructure/adapter/out/redis/RedisProcessedOperationStore.java` |
| Puerto | `domain/port/out/ProcessedOperationStore.java` |
| Configuración | `application.yaml` → `quarkus.redis.hosts` y `reconciliation.idempotency.ttl` |
| Variable | `REDIS_HOSTS` |
| Test | `src/test/.../redis/RedisProcessedOperationStoreTest.java` |

Uso único: filtro rápido de idempotencia. Clave `dedup:{operationReference}`, operación
`setGet` con `NX` + `EX`. La garantía real es el índice `UNIQUE` de la base.

## Integración con tópicos Kafka

| | |
| --- | --- |
| Configuración | `application.yaml` → bloques `kafka:` y `mp.messaging` |
| Consumers | `infrastructure/adapter/in/kafka/PaymentConsumer.java` · `CoreTransactionConsumer.java` |
| Publisher | `infrastructure/adapter/out/messaging/KafkaReconciliationEventPublisher.java` |
| Contratos Avro | `src/main/avro/*.avsc` |
| Test | `src/test/.../in/kafka/ConsumerWiringTest.java` |

Los nombres de tópico no están en el repositorio, llegan por entorno:

| Canal | Variable |
| --- | --- |
| `payments-in` | `KAFKA_TOPIC_PAYMENTS_IN` |
| DLQ de pagos | `KAFKA_TOPIC_PAYMENTS_DLQ` |
| `core-transactions-in` | `KAFKA_TOPIC_CORE_TRANSACTIONS_IN` |
| DLQ de movimientos | `KAFKA_TOPIC_CORE_TRANSACTIONS_DLQ` |
| `results-out` | `KAFKA_TOPIC_RESULTS_OUT` |
| `discrepancies-out` | `KAFKA_TOPIC_DISCREPANCIES_OUT` |
