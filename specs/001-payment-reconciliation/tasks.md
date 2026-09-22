---

description: "Task list for Conciliación de Pagos"
---

# Tasks: Conciliación de Pagos

**Input**: Design documents from `/specs/001-payment-reconciliation/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: INCLUIDOS y obligatorios. No son opcionales aquí: la constitución XIII nombra
explícitamente cinco tests que deben existir (matriz de transiciones, validación de cada
Value Object, igualdad de `Amount`, idempotencia por consumer, mappers de ida y vuelta).

**Organization**: agrupadas por historia de usuario, para que cada una sea implementable y
verificable por separado.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: paralelizable — fichero distinto, sin dependencias pendientes
- **[Story]**: US1, US2, US3
- Rutas exactas en cada tarea

## Path Conventions

Proyecto Maven único, paquete base `com.bancatest.reconciliation`, estructura hexagonal
obligatoria (constitución V). Fuente: `src/main/java/...`, tests: `src/test/java/...`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: construir el esqueleto del build. Nada de lógica.

- [X] T001 Crear proyecto Maven con `pom.xml` en la raíz: BOM de Quarkus 3.x, Java 21, empaquetado JVM (no nativo)
- [X] T002 Añadir extensiones Quarkus en `pom.xml`: `quarkus-rest`, `quarkus-config-yaml`, `quarkus-hibernate-orm-panache`, `quarkus-jdbc-postgresql`, `quarkus-redis-client`, `quarkus-smallrye-reactive-messaging-kafka`, `quarkus-confluent-registry-avro`, `quarkus-rest-client`, `quarkus-logging-json`
- [X] T003 Añadir en `pom.xml` el repositorio Maven de Confluent y la dependencia `io.confluent:kafka-avro-serializer`, **excluyendo `jakarta.ws.rs:jakarta.ws.rs-api`** (R-06)
- [X] T004 Comprobar con `mvn dependency:tree -Dincludes=org.postgresql:postgresql` qué versión resuelve el BOM. Si es exactamente 42.7.8, fijar en `<properties>` de `pom.xml` la última 42.7.x (>= 42.7.9), con comentario que remita a R-13. Si ya es >= 42.7.9, **no tocar nada**
- [X] T005 [P] Configurar la generación de código Avro. **No se usa `avro-maven-plugin`**: `quarkus-confluent-registry-avro` arrastra `quarkus-avro`, cuyo codegen ya compila `src/main/avro`, y tener ambos producía clases duplicadas. Se configura en `application.yaml` con **`avro.codegen.enableDecimalLogicalType: true`** (su default `false` genera `ByteBuffer` en vez de `BigDecimal`) y `avro.codegen.stringType: String`. Ojo al prefijo: es `avro.codegen.*`, sin `quarkus.` (R-12)
- [X] T006 [P] Configurar `openapi-generator-maven-plugin` en `pom.xml`: goal `generate` en `generate-sources`, `generatorName=jaxrs-spec`, `library=quarkus`, y `configOptions` **`interfaceOnly=true`, `useJakartaEe=true`, `useSwaggerAnnotations=false`, `dateLibrary=java8`, `returnResponse=false`** (los tres últimos defaults violan el plan o la constitución X — ver D-01)
- [X] T007 [P] Configurar `annotationProcessorPaths` en `maven-compiler-plugin` de `pom.xml` en este orden exacto: `lombok`, `lombok-mapstruct-binding`, `mapstruct-processor`
- [X] T008 [P] Crear `checkstyle.xml` en la raíz y enlazarlo desde `pom.xml` con `severity=error` en fase `validate`: complejidad ciclomática máx. 10, métodos máx. 40 líneas, parámetros máx. 5, línea máx. 140, imports sin estrella ni sin usar. **Solo reglas semánticas, ninguna de formato**
- [X] T009 [P] Añadir `ImportControl` de Checkstyle en `checkstyle.xml`: `domain` no importa `jakarta.persistence`, `io.smallrye`, `io.quarkus`, `org.apache.kafka`, `org.apache.avro` ni `infrastructure`; `application` no importa `infrastructure`; `lombok` solo en `infrastructure.adapter.out.persistence.entity`
- [X] T010 [P] Configurar `spotless-maven-plugin` con `palantir-java-format` en `pom.xml`: `check` ligado a `verify`. El build nunca modifica archivos
- [X] T011 [P] Crear `.env.example` en la raíz con las **quince** variables y valores vacíos, y `.gitignore` con `.env`, `target/`
- [X] T012 [P] Crear el árbol de paquetes vacío bajo `src/main/java/com/bancatest/reconciliation/` según la estructura obligatoria de la constitución V

**Checkpoint**: `mvn validate` pasa; Checkstyle y Spotless están activos y fallan el build.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: contratos, configuración y núcleo del dominio. Lo comparten las tres historias.

**⚠️ CRITICAL**: ninguna historia de usuario puede empezar hasta terminar esta fase.

### Contratos y esquema

- [X] T013 [P] Copiar los cuatro `.avsc` de `specs/001-payment-reconciliation/contracts/` a `src/main/avro/`: `PaymentExecuted.avsc`, `CoreTransactionReported.avsc`, `ReconciliationMatched.avsc`, `DiscrepancyDetected.avsc`
- [X] T014 [P] Copiar `reconciliation-api.yaml` a `src/main/resources/openapi/reconciliation-api.yaml`. **Mantener `openapi: 3.0.3`**, no subirlo a 3.1 (D-01)
- [X] T015 [P] Escribir `db/schema.sql` con la tabla `reconciliations`: `id UUID PK`, `operation_reference VARCHAR(64) NOT NULL UNIQUE`, `account_number VARCHAR(34) NOT NULL`, `payment_amount NUMERIC(19,2) NOT NULL`, `payment_currency VARCHAR(3) NOT NULL`, `core_amount NUMERIC(19,2) NULL`, `core_currency VARCHAR(3) NULL`, `difference_amount NUMERIC(19,2) NULL`, `difference_currency VARCHAR(3) NULL`, `status VARCHAR(16) NOT NULL`, `created_at TIMESTAMPTZ NOT NULL`, `resolved_at TIMESTAMPTZ NULL`. **El índice UNIQUE sobre `operation_reference` es la garantía real de idempotencia**

### Configuración

- [X] T016 Crear `src/main/resources/application.yaml`: datasource con `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}` sin valores por defecto; `quarkus.hibernate-orm.schema-management.strategy=none` y `sql-load-script=no-file` (R-01); `quarkus.devservices.enabled=false` (R-02); `quarkus.datasource.jdbc.min-size=5` y `max-size=16` (R-13); Redis con `${REDIS_HOSTS}`. Incluir además el **bloque de configuración de negocio** que alimenta `ReconciliationConfig` (T018): **tolerancia `amount: "1.00"` y `currency: PEN`** (FR-008) y el TTL del filtro de idempotencia. Sin esos dos valores la aplicación **no arranca**, porque la constitución IV prohíbe valores por defecto
- [X] T017 Añadir a `application.yaml` el bloque global `kafka.`: `bootstrap.servers`, `security.protocol=SASL_SSL`, `sasl.mechanism=PLAIN`, `sasl.jaas.config` con `PlainLoginModule` **terminado en punto y coma**, `schema.registry.url`, `basic.auth.credentials.source=USER_INFO` y `basic.auth.user.info=${SCHEMA_REGISTRY_API_KEY}:${SCHEMA_REGISTRY_API_SECRET}` (R-09, R-10)
- [X] T018 Crear `infrastructure/config/ReconciliationConfig.java` como interfaz `@ConfigMapping`: tolerancia (importe y moneda) y TTL del filtro de idempotencia. **Nunca se inyecta en `domain`**: sus valores entran convertidos en Value Objects

### Value Objects (constitución VII)

- [X] T019 [P] Crear `domain/model/vo/Currency.java` como `record Currency(String code)`. Valida: código ISO-4217, exactamente 3 letras mayúsculas
- [X] T020 [P] Crear `domain/model/vo/OperationReference.java` como `record OperationReference(String value)`. Valida: "No nula, no en blanco"
- [X] T021 [P] Crear `domain/model/vo/AccountNumber.java` como `record AccountNumber(String value)`. Valida: "No nulo, no en blanco"
- [X] T022 [P] Crear `domain/model/vo/ReconciliationId.java` como `record ReconciliationId(UUID value)`. Valida: no nulo
- [X] T023 [P] Crear las tres excepciones de dominio en `src/main/java/com/bancatest/reconciliation/domain/exception/`: `InvalidAmountException.java`, `CurrencyMismatchException.java`, `IllegalStateTransitionException.java` (esta última lleva estado origen y destino). Todas **no checked**
- [X] T024 Crear `domain/model/vo/Amount.java` como `record Amount(BigDecimal value, Currency currency)` (depende de T019, T023). Constructor compacto normaliza a **escala 2 con `RoundingMode.HALF_UP` explícito**. Valida: "`value` no nulo, no negativo; `currency` no nula". **Sobrescribe `equals` y `hashCode` comparando con `compareTo`** sobre el valor normalizado. Prohibido `new BigDecimal(double)`
- [X] T025 Añadir a `Amount.java` los métodos `subtract(Amount other)` —lanza `CurrencyMismatchException` si las monedas difieren— e `isWithinTolerance(Amount tolerance)`, por **resta directa, sin división**

### Agregado y máquina de estados

- [X] T026 Crear `domain/model/vo/ReconciliationStatus.java`: enum `PENDING`, `MATCHED`, `MISMATCHED` con las transiciones válidas en un `Map<ReconciliationStatus, Set<ReconciliationStatus>>` estático e inmutable, expuesto por `canTransitionTo(ReconciliationStatus)`. Solo `PENDING → MATCHED` y `PENDING → MISMATCHED` son válidas
- [X] T027 Crear `domain/model/Reconciliation.java` (depende de T024, T026): **constructor privado**, factories estáticas `start(...)` (valida invariantes, nace PENDING) y `reconstitute(...)` (solo rehidrata, sin validar, uso exclusivo del mapper de persistencia). **Sin setters públicos**. `coreAmount`, `difference` y `resolvedAt` se exponen como `Optional`, nunca `null`
- [X] T028 Añadir a `Reconciliation.java` los métodos de negocio `match(...)`, `markAsMismatched(...)` e `isResolved()`. Ambas transiciones validan con `canTransitionTo` **antes** de aplicarse y lanzan `IllegalStateTransitionException` si no procede. Javadoc obligatorio: qué invariantes protege (constitución XI)

### Puerto de repositorio y persistencia

- [X] T029 [P] Crear `domain/port/out/ReconciliationRepository.java`: `void save(Reconciliation)` y `Optional<Reconciliation> findByOperationReference(OperationReference)`. Javadoc con el contrato que ofrece
- [X] T030 Crear `infrastructure/adapter/out/persistence/entity/ReconciliationEntity.java` como entidad JPA con las columnas de T015. `@Enumerated(EnumType.STRING)` para `status`. **Único lugar con Lombok**, y solo `@Getter`, `@Setter`, `@NoArgsConstructor(access = PROTECTED)`
- [X] T031 Crear `infrastructure/adapter/out/persistence/mapper/ReconciliationEntityMapper.java` con MapStruct: dominio ↔ entidad JPA. Invoca `Reconciliation.reconstitute(...)` vía `@ObjectFactory` o método `default`; **nunca `new` ni setters**
- [X] T032 Crear `infrastructure/adapter/out/persistence/PanacheReconciliationRepository.java` implementando `ReconciliationRepository` sobre `PanacheRepository<ReconciliationEntity>`. **Prohibido `PanacheEntity`**
- [X] T033 Crear `infrastructure/adapter/out/persistence/ReconciliationIdGenerator.java`: singleton que expone `Generators.timeBasedEpochGenerator()` y genera UUIDv7 con `.generate()` (R-05)
- [X] T034 Crear `infrastructure/adapter/out/persistence/mapper/MapperConfig.java` como `@MapperConfig` compartido: `componentModel = MappingConstants.ComponentModel.JAKARTA_CDI` y `unmappedTargetPolicy = ReportingPolicy.ERROR` (R-07)

### Tests del núcleo (constitución XIII)

- [X] T035 [P] Crear `src/test/java/com/bancatest/reconciliation/domain/model/vo/ValueObjectValidationTest.java`: test de validación en construcción de cada Value Object. JUnit puro, **sin `@QuarkusTest` ni contexto CDI**
- [X] T036 [P] Crear `src/test/java/com/bancatest/reconciliation/domain/model/vo/AmountTest.java`: verifica que `120.0` y `120.00` son iguales, y que comparar u operar entre monedas distintas lanza `CurrencyMismatchException`
- [X] T037 [P] Crear `src/test/java/com/bancatest/reconciliation/domain/model/vo/ReconciliationStatusTest.java`: test parametrizado con la **matriz completa de 9 combinaciones**, verificando que solo las 2 declaradas se permiten y las otras 7 se rechazan
- [X] T038 [P] Crear `src/test/java/com/bancatest/reconciliation/infrastructure/adapter/out/persistence/mapper/ReconciliationEntityMapperTest.java`: test de ida y vuelta dominio ↔ entidad

**Checkpoint**: dominio completo y testeado sin infraestructura. Las historias pueden empezar.

---

## Phase 3: User Story 1 - Conciliación automática de un pago con su movimiento (Priority: P1) 🎯 MVP

**Goal**: un pago reportado crea una conciliación PENDING; el movimiento del core la resuelve
comparando importes contra la tolerancia, y el resultado se publica en el canal que
corresponde.

**Independent Test**: enviar un `PaymentExecuted` y después su `CoreTransactionReported`, y
verificar que la conciliación queda resuelta con el estado correcto y que el resultado aparece
publicado en el canal correspondiente.

### Tests for User Story 1

> Escribir primero y comprobar que fallan antes de implementar.

- [X] T039 [P] [US1] Crear `src/test/java/com/bancatest/reconciliation/domain/model/ReconciliationToleranceTest.java` (**SC-002**): los **siete casos de importes** del spec — 100.00/100.00→MATCHED dif 0.00; 100.00/99.50→MATCHED dif 0.50; 100.00/99.00→MATCHED dif 1.00 (límite inclusivo); 100.00/98.99→MISMATCHED dif 1.01; 100.00/95.00→MISMATCHED dif 5.00; 100.00/100.50→MISMATCHED dif -0.50. JUnit puro
- [X] T040 [P] [US1] Crear `src/test/java/com/bancatest/reconciliation/infrastructure/adapter/in/kafka/mapper/PaymentAvroMapperTest.java`: test de ida y vuelta Avro → comando
- [X] T041 [P] [US1] Crear `src/test/java/com/bancatest/reconciliation/infrastructure/adapter/in/kafka/mapper/CoreTransactionAvroMapperTest.java`: test de ida y vuelta Avro → comando
- [X] T042 [P] [US1] Crear `src/test/java/com/bancatest/reconciliation/infrastructure/adapter/out/messaging/mapper/ReconciliationEventMapperTest.java`: test de ida y vuelta dominio → Avro de salida
- [X] T043 [P] [US1] Crear `src/test/java/com/bancatest/reconciliation/infrastructure/adapter/in/kafka/PaymentConsumerTest.java` con `InMemoryConnector` de SmallRye (**SC-001**, **SC-004**): un pago válido crea una conciliación PENDING y **no publica nada**. Sin contenedores
- [X] T044 [P] [US1] Crear `src/test/java/com/bancatest/reconciliation/infrastructure/adapter/in/kafka/CoreTransactionConsumerTest.java` con `InMemoryConnector`: el movimiento resuelve la conciliación y publica en el canal correcto

### Implementation for User Story 1

- [X] T045 [P] [US1] Crear los comandos en `src/main/java/com/bancatest/reconciliation/domain/port/in/`: `ReconcilePaymentCommand.java` como `record ReconcilePaymentCommand(OperationReference, AccountNumber, Amount, Instant)` y `RegisterCoreTransactionCommand.java` como `record RegisterCoreTransactionCommand(OperationReference, Amount, Instant)`. **El `eventId` del Avro no entra en los comandos**: es informativo (FR-018)
- [X] T046 [P] [US1] Crear `domain/port/in/ReconcilePaymentUseCase.java` y `domain/port/in/RegisterCoreTransactionUseCase.java` como interfaces, con Javadoc del contrato
- [X] T047 [P] [US1] Crear `domain/port/out/ReconciliationEventPublisher.java`: `publishMatched(Reconciliation)` y `publishMismatched(Reconciliation)`
- [X] T048 [US1] Crear `application/service/ReconcilePaymentService.java` (depende de T045, T046): construye el agregado con `Reconciliation.start(...)` y lo persiste. Recibe puertos, nunca implementaciones
- [X] T049 [US1] Crear `application/service/RegisterCoreTransactionService.java`: busca por `OperationReference`; si el `Optional` viene vacío descarta con WARN (FR-021); **comprueba `isResolved()` ANTES de invocar transición alguna** (FR-020); calcula la diferencia y aplica `match(...)` o `markAsMismatched(...)` según la tolerancia
- [X] T050 [P] [US1] Crear `infrastructure/adapter/in/kafka/mapper/PaymentAvroMapper.java` con MapStruct: `PaymentExecuted` → `ReconcilePaymentCommand`. **Prohibido mapear Avro directo al agregado**
- [X] T051 [P] [US1] Crear `infrastructure/adapter/in/kafka/mapper/CoreTransactionAvroMapper.java`: `CoreTransactionReported` → `RegisterCoreTransactionCommand`
- [X] T052 [P] [US1] Crear `infrastructure/adapter/out/messaging/mapper/ReconciliationEventMapper.java`: dominio → `ReconciliationMatched` y `DiscrepancyDetected`
- [X] T053 [US1] Crear `infrastructure/adapter/in/kafka/PaymentConsumer.java`: `@Incoming("payments-in")` con **`@RunOnVirtualThread`**. Offset commiteado después de procesar
- [X] T054 [US1] Crear `infrastructure/adapter/in/kafka/CoreTransactionConsumer.java`: `@Incoming("core-transactions-in")` con `@RunOnVirtualThread`
- [X] T055 [US1] Crear `infrastructure/adapter/out/messaging/KafkaReconciliationEventPublisher.java` implementando `ReconciliationEventPublisher`. **Publica después de confirmar la transacción** (FR-014b), con `operationReference` como key del mensaje
- [X] T056 [US1] Añadir a `application.yaml` los cuatro canales: `payments-in`, `core-transactions-in`, `results-out`, `discrepancies-out`, con `connector=smallrye-kafka`, `topic=${KAFKA_TOPIC_*}`, `enable.auto.commit=false`, `partition.assignment.strategy=CooperativeStickyAssignor` y **`auto.register.schemas=false`** en los salientes (su default es `true` — R-06)

**Checkpoint**: US1 funciona de punta a punta y es demostrable. **Es el MVP.**

---

## Phase 4: User Story 2 - Descarte seguro de duplicados y entradas inválidas (Priority: P2)

**Goal**: reenvíos y datos inválidos se absorben sin corromper el resultado ni detener el
procesamiento.

**Independent Test**: reenviar el mismo pago dos veces y verificar que existe una sola
conciliación; enviar un movimiento sobre una conciliación ya resuelta y verificar que no cambia
ni publica nada.

### Tests for User Story 2

- [X] T057 [P] [US2] Crear `src/test/java/com/bancatest/reconciliation/infrastructure/adapter/in/kafka/PaymentIdempotencyTest.java` (**SC-003**): procesa el mismo `PaymentExecuted` dos veces y verifica **un solo efecto** — una conciliación, una sola publicación. Debe registrarse INFO de descarte, **no ERROR**, y el mensaje **no** debe acabar en la DLQ
- [X] T058 [P] [US2] Crear `src/test/java/com/bancatest/reconciliation/infrastructure/adapter/in/kafka/CoreTransactionIdempotencyTest.java`: un movimiento sobre una conciliación ya MATCHED se descarta, el estado no cambia, no se publica nada y **no se lanza `IllegalStateTransitionException`**
- [X] T059 [P] [US2] Crear `src/test/java/com/bancatest/reconciliation/application/service/InvalidInputTest.java` (**SC-005**): monto `0.00`, monto negativo, moneda `USD`, referencia de operación en blanco y cuenta en blanco no crean ni modifican conciliación alguna (FR-022). Verificar además que **un mensaje válido enviado a continuación se procesa con normalidad** (**SC-007**): un descarte no detiene el consumo
- [X] T060 [P] [US2] Crear `src/test/java/com/bancatest/reconciliation/infrastructure/adapter/out/redis/RedisProcessedOperationStoreTest.java`: la segunda llamada con la misma referencia devuelve `false`. Contra el Redis real ya configurado, con **prefijo identificable en los datos y limpieza de lo creado**

### Implementation for User Story 2

- [X] T061 [P] [US2] Crear `domain/port/out/ProcessedOperationStore.java`: `boolean markAsProcessed(OperationReference)` — `true` si quedó marcada ahora, `false` si ya lo estaba. Javadoc del contrato
- [X] T062 [US2] Crear `infrastructure/adapter/out/redis/RedisProcessedOperationStore.java` con `RedisDataSource` bloqueante: `ds.value(String.class)` y **`setAndChanged(key, value, new SetArgs().nx().ex(ttl))`** sobre la clave `dedup:{operationReference}`. **No usar `set(K,V,SetArgs)`: devuelve `void`** (R-08)
- [X] T063 [US2] Añadir la validación de negocio a `ReconcilePaymentService.java`: monto estrictamente mayor que cero y moneda PEN. Inválido → excepción que el consumer deja propagar, para que la estrategia DLQ lo derive (FR-022)
- [X] T064 [US2] Añadir la misma validación a `RegisterCoreTransactionService.java`
- [X] T065 [US2] Añadir a `ReconcilePaymentService.java` el filtro previo con `ProcessedOperationStore` y el manejo de la violación del `UNIQUE`. **Un duplicado rechazado se trata como procesamiento correcto, no como error** (FR-019)
- [X] T066 [US2] Añadir a `application.yaml` en ambos canales de entrada: `failure-strategy=dead-letter-queue` y `dead-letter-queue.topic=${KAFKA_TOPIC_*_DLQ}`. **Declarar el topic explícitamente**: el default es `dead-letter-topic-$channel`, que no existe en Confluent Cloud (R-03)
- [X] T067 [US2] Añadir los niveles de log definidos en el plan a `application/service/ReconcilePaymentService.java`, `application/service/RegisterCoreTransactionService.java`, `infrastructure/adapter/in/kafka/PaymentConsumer.java` e `infrastructure/adapter/in/kafka/CoreTransactionConsumer.java`: INFO en transiciones y descarte por idempotencia; WARN en discrepancia detectada, movimiento sobre conciliación resuelta y movimiento sin conciliación. **Nunca loguear el payload completo ni el número de cuenta completo**

**Checkpoint**: US1 y US2 funcionan de forma independiente.

---

## Phase 5: User Story 3 - Consulta del detalle de una conciliación (Priority: P3)

**Goal**: consultar el estado de una conciliación por su referencia de operación y, si es
discrepante, su diferencia exacta.

**Independent Test**: consultar una conciliación existente y verificar que devuelve su
detalle; consultar una referencia inexistente y verificar que responde no encontrada.

### Tests for User Story 3

- [X] T068 [P] [US3] Crear `src/test/java/com/bancatest/reconciliation/infrastructure/adapter/in/rest/ReconciliationResourceTest.java` (**SC-006**): `GET /v1/reconciliations/{ref}` devuelve 200 con el detalle para una conciliación MISMATCHED, y **404 con `Content-Type: application/problem+json`** para una referencia inexistente

### Implementation for User Story 3

- [X] T069 [P] [US3] Crear `domain/port/in/QueryReconciliationUseCase.java` devolviendo `Optional<Reconciliation>`. **Prohibido `isPresent()` seguido de `get()`** en los consumidores del puerto
- [X] T070 [US3] Crear `application/service/QueryReconciliationService.java` delegando en `ReconciliationRepository.findByOperationReference`
- [X] T071 [P] [US3] Crear `infrastructure/adapter/in/rest/mapper/ReconciliationResponseMapper.java`: dominio → DTO generado. **Los montos se serializan como `string`, nunca `number`**. Prohibido exponer entidades de dominio o JPA
- [X] T072 [US3] Crear `infrastructure/adapter/in/rest/ReconciliationResource.java` **implementando la interfaz JAX-RS generada**, con `@RunOnVirtualThread`. Prohibido `@Schema`, `@Operation` y `@APIResponse` en el código
- [X] T073 [US3] Crear `infrastructure/adapter/in/rest/NotFoundExceptionMapper.java`: produce el 404 con cuerpo `ProblemDetail` RFC 7807. Es la vía del 404, no devolver `Response` sin tipo desde la interfaz

**Checkpoint**: las tres historias son funcionales de forma independiente.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T074 [P] Crear `docs/glossary.md` con las **ocho** equivalencias del glosario de la constitución XI: Conciliación→Reconciliation, Pago reportado→ReportedPayment, Movimiento del core→CoreTransaction, Referencia de operación→OperationReference, Monto→Amount, Moneda→Currency, Diferencia→AmountDifference, Tolerancia→Tolerance
- [X] T075 [P] Escribir `README.md`: las **variables que nombran los topics** con el rol de cada una (los nombres literales no van en el repo), y las **dos limitaciones conocidas** — orden de entrega entre canales, y pérdida del evento de publicación por ausencia de outbox
- [X] T076 Añadir `operationReference` al MDC en `infrastructure/adapter/in/kafka/PaymentConsumer.java`, `infrastructure/adapter/in/kafka/CoreTransactionConsumer.java` e `infrastructure/adapter/in/rest/ReconciliationResource.java`, **con limpieza en `finally`**
- [X] T077 Barrer `src/main/java/com/bancatest/reconciliation/` verificando que no queda ningún `System.out.println` ni `printStackTrace`, que **no hay `synchronized` en ninguna sección con I/O** —se usa `ReentrantLock`, constitución IX—, que se usa JBoss Logging, y que toda excepción se loguea **con la excepción como argumento, no con `getMessage()`**
- [X] T078 Completar el Javadoc en `src/main/java/com/bancatest/reconciliation/domain/`: invariantes del agregado, validaciones de cada Value Object, contrato de cada puerto. **Sin Javadoc autoevidente** que repita el nombre del método
- [X] T079 Ejecutar `mvn spotless:apply` y después `mvn verify` hasta que pase en verde: Spotless `check`, Checkstyle `severity=error` y toda la suite
- [ ] T080 Ejecutar los siete escenarios de validación de `quickstart.md` contra la infraestructura real
- [ ] T081 Verificar el pinning con `mvn quarkus:dev -Djdk.tracePinnedThreads=full`: **ninguna traza `onPinned` debe atravesar `org.postgresql`** (R-13)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Fase 1)**: sin dependencias
- **Foundational (Fase 2)**: depende de Setup. **BLOQUEA las tres historias**
- **US1 (Fase 3)**: depende de Foundational
- **US2 (Fase 4)**: depende de Foundational. Modifica ficheros de US1 (T063, T064, T065), así que en la práctica va después de US1
- **US3 (Fase 5)**: depende de Foundational. **Independiente de US1 y US2**: solo lee por el repositorio
- **Polish (Fase 6)**: depende de las historias que se quieran entregar

### User Story Dependencies

- **US1 (P1)**: sin dependencias de otras historias
- **US2 (P2)**: añade comportamiento a los servicios de US1. Es la única con acoplamiento real
- **US3 (P3)**: sin dependencias de US1 ni US2. Puede desarrollarse en paralelo a ambas

### Within Each User Story

Tests primero y en rojo → comandos y puertos → servicios → mappers → adaptadores → configuración.

### Parallel Opportunities

- Fase 1: T005 a T012 en paralelo, tras T001–T004
- Fase 2: T013–T015 en paralelo; los Value Objects T019–T023 en paralelo; los tests T035–T038 en paralelo
- Fase 3: los seis tests T039–T044 en paralelo; los mappers T050–T052 en paralelo
- Fase 4: los cuatro tests T057–T060 en paralelo
- Con equipo: **US1 y US3 pueden ir en paralelo** desde el final de la Fase 2

---

## Parallel Example: User Story 1

```bash
# Los seis tests de US1, a la vez:
Task: "ReconciliationToleranceTest — los 7 casos de importes"
Task: "PaymentAvroMapperTest — ida y vuelta"
Task: "CoreTransactionAvroMapperTest — ida y vuelta"
Task: "ReconciliationEventMapperTest — ida y vuelta"
Task: "PaymentConsumerTest — InMemoryConnector"
Task: "CoreTransactionConsumerTest — InMemoryConnector"

# Los tres mappers, a la vez:
Task: "PaymentAvroMapper en adapter/in/kafka/mapper"
Task: "CoreTransactionAvroMapper en adapter/in/kafka/mapper"
Task: "ReconciliationEventMapper en adapter/out/messaging/mapper"
```

---

## Implementation Strategy

### MVP First (solo User Story 1)

1. Fase 1: Setup
2. Fase 2: Foundational — **crítica, bloquea todo**
3. Fase 3: US1
4. **PARAR Y VALIDAR**: escenarios 1 y 2 de `quickstart.md`, la tabla de tolerancia completa
5. Demostrable: el servicio concilia y publica

### Incremental Delivery

1. Setup + Foundational → dominio testeado sin infraestructura
2. + US1 → **MVP**: concilia y publica
3. + US2 → resistente a reenvíos y datos inválidos
4. + US3 → consultable
5. + Polish → documentado y con el build en verde

### Parallel Team Strategy

1. El equipo hace Setup y Foundational en conjunto
2. Terminada la Fase 2: Dev A con US1, Dev B con US3 (son independientes)
3. US2 entra cuando US1 esté cerrada, porque modifica sus servicios

---

## Notes

- Ninguna tarea añade funcionalidad fuera de los 22 FR del spec (constitución I)
- Los valores de configuración señalados con **R-xx** están verificados en `research.md`. Varios defaults violan la constitución si se dejan sin declarar: `enableDecimalLogicalType`, `dateLibrary`, `useSwaggerAnnotations`, `auto.register.schemas`, `dead-letter-queue.topic`
- **Los seis nombres de topic no se escriben en ningún fichero del repositorio**: llegan por variable de entorno
- Sin Testcontainers y sin levantar contenedores desde ningún test (constitución XIII)
- Commit tras cada tarea o grupo lógico; parar en cualquier checkpoint para validar

---

## Phase 7: Convergence

Añadida por `/speckit-converge` el 2026-09-20, tras contrastar el código real contra spec,
plan, tasks y constitución. No se ha renumerado ni modificado ninguna tarea anterior.

- [X] T082 **FALSO POSITIVO, cerrada con evidencia.** Converge marcó como CRÍTico que `register()` autoinvoca `resolveInTransaction()` y que eso anularía `@Transactional`. **No es así en Quarkus.** ArC aplica interceptores generando una **subclase** que sobrescribe el método, de modo que `this` ya es la instancia interceptada y la llamada sí atraviesa la cadena. El razonamiento venía del modelo de Spring, que sí usa proxies dinámicos y donde la autoinvocación sí rompe. Verificado empíricamente: `TransactionManager.getStatus()` devuelve `STATUS_ACTIVE` (0) en las 12 llamadas a `save()` de la suite. Se dejó como regresión permanente un guard en `InMemoryReconciliationRepository.Repository.save()` que falla si no hay transacción activa, y se documentó en el Javadoc del método por qué funciona y bajo qué dos condiciones (no privado, no final) per FR-014b (contradicts — descartado)
- [X] T083 **Migrados a MapStruct.** Los cinco mappers son ahora interfaces `@Mapper(config = MapperConfig.class)`: los dos de entrada Avro, el de persistencia, el de eventos de salida y el de REST. Las conversiones de Value Object van en métodos `default` del propio mapper, y los campos que combinan o desenvuelven (monto+moneda, `Optional`) van como `expression`. El de persistencia usa `ReconciliationSnapshot` como destino intermedio y un `default` que llama a `reconstitute`: el agregado nunca se reconstruye con `new` ni setters. **Garantía verificada empíricamente**: al añadir un campo a la entidad, el build falla con `Unmapped target property: "campoNuevo"` per plan §MAPPERS (contradicts)
- [X] T084 Creado `infrastructure/adapter/out/persistence/mapper/MapperConfig.java` como `@MapperConfig` compartido por los cinco mappers: `componentModel = JAKARTA_CDI` (no `CDI`, que apunta al namespace `javax` y generaría beans que Quarkus no ve) y `unmappedTargetPolicy = ReportingPolicy.ERROR` per T034, research R-07 (missing)
- [X] T085 Actualizar `plan.md`, `research.md` (R-12) y T005: la generación Avro la hace el codegen de `quarkus-avro` con `avro.codegen.enableDecimalLogicalType: true`, no el `avro-maven-plugin`. Mantener ambos producía clases duplicadas, y el de Quarkus generaba `ByteBuffer` hasta configurarlo per plan §Estructura (contradicts)
- [X] T086 Añadir `ToleranceProvider` y `ReconciliationIdProvider` a la lista de puertos de salida en `plan.md` y `data-model.md`, con su razón: la constitución V prohíbe que `application` importe `infrastructure`, y el control de imports de Checkstyle lo bloqueó en el build per plan §port/out (unrequested)
- [X] T087 Registrar en `plan.md` las dependencias añadidas fuera de la lista original: `quarkus-rest-jackson` (sin ella no hay serialización JSON), `quarkus-hibernate-validator` (lo exige el código generado con `useBeanValidation`), y `mockito-core` y `awaitility` en scope test per plan §Primary Dependencies (unrequested)
