# Phase 1 — Data Model: Conciliación de Pagos

**Fecha**: 2026-09-20 | **Plan**: [plan.md](./plan.md) | **Spec**: [spec.md](./spec.md)

Nomenclatura según el glosario de la constitución XI. El dominio no importa ningún paquete de
framework.

---

## Aggregate Root — `Reconciliation`

Única entidad con identidad y ciclo de vida. Nada externo referencia sus partes internas.

| Campo | Tipo | Notas |
| --- | --- | --- |
| `id` | `ReconciliationId` | UUIDv7 generado en aplicación |
| `operationReference` | `OperationReference` | Clave de negocio, única |
| `accountNumber` | `AccountNumber` | Informativo, del pago reportado |
| `paymentAmount` | `Amount` | Monto del pago reportado |
| `coreAmount` | `Amount` | Nulo mientras PENDING; presente al resolverse |
| `difference` | `AmountDifference` | Nulo mientras PENDING. Atributo, no entidad |
| `status` | `ReconciliationStatus` | PENDING, MATCHED o MISMATCHED |
| `createdAt` | `Instant` | Alta del pago reportado |
| `resolvedAt` | `Instant` | Nulo mientras PENDING |

`coreAmount`, `difference` y `resolvedAt` son nulos internamente mientras la conciliación está
PENDING, pero **ningún getter del dominio devuelve `null`**: los tres se exponen como
`Optional<...>`, conforme a la constitución X.

### Construcción

- **Constructor privado.** No hay otra vía.
- **`static Reconciliation start(ReconciliationId, OperationReference, AccountNumber,
  Amount paymentAmount, Instant createdAt)`** — crea una conciliación nueva en PENDING.
  Valida invariantes: ningún argumento nulo, `paymentAmount` en PEN. Es el único camino para
  un agregado nuevo.
- **`static Reconciliation reconstitute(ReconciliationSnapshot)`** — rehidrata desde
  persistencia con todos los
  campos, incluidos estado, diferencia y `resolvedAt`. **No valida invariantes de negocio**:
  asume que lo persistido fue válido. Lo usa exclusivamente el mapper de persistencia.

  Recibe un **`ReconciliationSnapshot`** —un record con los nueve campos— en lugar de nueve
  argumentos sueltos. No es preferencia de estilo: Checkstyle rechaza métodos de más de cinco
  parámetros (constitución XII), y la firma original tenía nueve. Es además el único punto del
  dominio donde se toleran nulos, porque refleja columnas nulables; el agregado los reexpone
  como `Optional`.

### Métodos de negocio

Nombres de intención, sin setters.

- **`void match(Amount coreAmount, Amount difference, Instant resolvedAt)`** — transiciona a
  MATCHED. Valida la transición antes de aplicarla.
- **`void markAsMismatched(Amount coreAmount, Amount difference, Instant resolvedAt)`** —
  transiciona a MISMATCHED. Misma validación.
- **`boolean isResolved()`** — `true` si el estado es terminal. Es lo que consulta el caso de
  uso **antes** de invocar una transición (FR-020).

Ambos métodos de transición llaman a `status.canTransitionTo(target)` y lanzan
`IllegalStateTransitionException` con estado origen y destino si no es válida. Esa excepción
es red de seguridad: con `isResolved()` comprobado antes, el flujo normal nunca la dispara.

### Invariantes protegidas

1. Una conciliación nace siempre en PENDING y con `paymentAmount` válido en PEN.
2. El estado solo avanza por transiciones declaradas; MATCHED y MISMATCHED son terminales.
3. Al resolverse, `coreAmount`, `difference` y `resolvedAt` quedan poblados los tres a la vez.
   No existe un estado resuelto con diferencia ausente.
4. `difference` siempre comparte moneda con `paymentAmount`.

---

## Máquina de estados — `ReconciliationStatus`

Enum en `domain/model/vo`, sin librería externa.

```text
PENDING    → MATCHED
PENDING    → MISMATCHED
MATCHED    → ∅   (terminal)
MISMATCHED → ∅   (terminal)
```

Las transiciones válidas viven en un `Map<ReconciliationStatus, Set<ReconciliationStatus>>`
estático e inmutable, expuesto por `canTransitionTo(ReconciliationStatus target)`.

Matriz completa — es la que recorre el test parametrizado (constitución XIII):

| Origen ↓ / Destino → | PENDING | MATCHED | MISMATCHED |
| --- | --- | --- | --- |
| **PENDING** | ✗ | ✓ | ✓ |
| **MATCHED** | ✗ | ✗ | ✗ |
| **MISMATCHED** | ✗ | ✗ | ✗ |

9 combinaciones, 2 permitidas, 7 rechazadas. Se persiste con `name()`; `ordinal()` está
prohibido.

---

## Value Objects

Todos son `record` inmutables que validan en construcción: si son inválidos, no existen.

### `Amount`

```text
record Amount(BigDecimal value, Currency currency)
```

- Se construye desde `String` o `BigDecimal`. **`new BigDecimal(double)` está prohibido**:
  introduce el error de representación binaria que toda esta cadena existe para evitar.
- El constructor compacto normaliza a **escala 2 con `RoundingMode.HALF_UP`**, explícito.
- Valida: `value` no nulo, no negativo; `currency` no nula.
- **`equals` y `hashCode` sobrescritos**: comparan con `compareTo` sobre el valor normalizado,
  no con `BigDecimal.equals`. Sin esto, `120.0` y `120.00` serían distintos. Es la única razón
  legítima para sobrescribir estos métodos en un record aquí.
- `subtract(Amount other)` devuelve un `Amount`. Si las monedas difieren lanza
  `CurrencyMismatchException`. Nunca suma ni compara entre monedas.
- `isWithinTolerance(Amount tolerance)` — comparación por resta directa, sin división: la
  tolerancia es un monto fijo, no un porcentaje.

### `AmountDifference`

```text
record AmountDifference(BigDecimal value, Currency currency)
```

**Tipo propio, distinto de `Amount`, por una razón que se descubrió al implementar**: la
diferencia **puede ser negativa** cuando el core cobró de más (FR-007), y `Amount` valida "no
negativo". Con un solo tipo, `-0.50` era inexpresable.

El glosario de la constitución XI ya lo anticipaba —*Diferencia → AmountDifference*—; la
primera versión de este documento lo pasó por alto y reutilizó `Amount`.

- Normaliza a escala 2 con `RoundingMode.HALF_UP`, como `Amount`.
- `equals`/`hashCode` por `compareTo`, nunca `BigDecimal.equals`.
- `isWithinTolerance(Amount tolerance)`: cierto si es cero, o positiva y **menor o igual** a
  la tolerancia. Toda diferencia negativa es falsa, sin importar su magnitud.
- `Amount.subtract(Amount)` devuelve un `AmountDifference`, no un `Amount`.

### `Currency`

```text
record Currency(String code)
```

Código ISO-4217 de 3 letras mayúsculas. Valida el formato. En esta feature solo PEN es
aceptable a nivel de negocio, pero esa regla es del caso de uso, no del VO.

### `OperationReference`

```text
record OperationReference(String value)
```

No nula, no en blanco. Es la clave de negocio y el único criterio de idempotencia (FR-018).

### `AccountNumber`

```text
record AccountNumber(String value)
```

No nulo, no en blanco. Informativo. **Nunca se loguea completo** (regla de logs del plan).

### `ReconciliationId`

```text
record ReconciliationId(UUID value)
```

No nulo. Se genera con `Generators.timeBasedEpochGenerator().generate()` (UUIDv7, ver R-05).

---

## Comandos de entrada

Records en `domain/port/in`. El movimiento del core llega como comando y **no se persiste**.

```text
record ReconcilePaymentCommand(
    OperationReference operationReference,
    AccountNumber accountNumber,
    Amount amount,
    Instant occurredAt)

record RegisterCoreTransactionCommand(
    OperationReference operationReference,
    Amount amount,
    Instant occurredAt)
```

El `eventId` de los mensajes Avro **no entra en los comandos**: la clarificación fijó que es
informativo y que no participa en la idempotencia.

---

## Puertos de salida

Interfaces en `domain/port/out`. Ninguna menciona un tipo de framework.

```text
interface ReconciliationRepository
    void save(Reconciliation reconciliation)
    Optional<Reconciliation> findByOperationReference(OperationReference reference)

interface ProcessedOperationStore
    boolean markAsProcessed(OperationReference reference)   // false si ya estaba

interface ReconciliationEventPublisher
    void publishMatched(Reconciliation reconciliation)
    void publishMismatched(Reconciliation reconciliation)

interface ToleranceProvider
    Amount tolerance()

interface ReconciliationIdProvider
    ReconciliationId nextId()
```

**`ToleranceProvider` y `ReconciliationIdProvider` no estaban en el diseño original.** Los
obligó el build, no una preferencia: la capa `application` necesitaba la tolerancia, que vive
en una interfaz `@ConfigMapping` de `infrastructure`, y el control de imports de Checkstyle
rechazó ese import. La constitución IV ya anticipaba la salida —"sus valores entran
convertidos en Value Objects"— y un puerto es la forma de hacerlo. Lo mismo con la generación
de identidades: `Generators.timeBasedEpochGenerator()` es una librería externa que el dominio
no debe conocer.

Ambos devuelven Value Objects ya construidos, así que nada inward de la frontera sabe de dónde
salieron.

`markAsProcessed` devuelve `boolean`, no `Optional`: la pregunta es binaria — `true` si
quedó marcada ahora, `false` si ya lo estaba.

Su implementación en Redis es
`setAndChanged(key, value, new SetArgs().nx().ex(ttl))`, **no** `set(...)`: esa sobrecarga
devuelve `void` y no permitiría responder la pregunta. Ver R-08.

---

## Persistencia

Una sola tabla. La entidad JPA es una clase distinta del agregado, con mapper explícito entre
ambas, y vive en `infrastructure/adapter/out/persistence/entity`. Es el único lugar donde se
permite Lombok, y solo `@Getter`, `@Setter` y `@NoArgsConstructor(access = PROTECTED)`.

### Tabla `reconciliations`

| Columna | Tipo | Restricciones |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `operation_reference` | `VARCHAR(64)` | **NOT NULL, UNIQUE** |
| `account_number` | `VARCHAR(34)` | NOT NULL. 34 es la longitud máxima de un IBAN; el spec no fija longitud de cuenta, así que se adopta ese techo por ser el más amplio del estándar |
| `payment_amount` | `NUMERIC(19,2)` | NOT NULL |
| `payment_currency` | `VARCHAR(3)` | NOT NULL |
| `core_amount` | `NUMERIC(19,2)` | NULL hasta resolverse |
| `core_currency` | `VARCHAR(3)` | NULL hasta resolverse |
| `difference_amount` | `NUMERIC(19,2)` | NULL hasta resolverse |
| `difference_currency` | `VARCHAR(3)` | NULL hasta resolverse |
| `status` | `VARCHAR(16)` | NOT NULL, `name()` del enum |
| `created_at` | `TIMESTAMPTZ` | NOT NULL |
| `resolved_at` | `TIMESTAMPTZ` | NULL hasta resolverse |

El **índice `UNIQUE` sobre `operation_reference` es la garantía real de idempotencia**
(constitución VIII). Redis solo la acelera.

Cada monto usa dos columnas, importe y moneda, nunca una cadena combinada. `status` se mapea
con `@Enumerated(EnumType.STRING)` en la entidad JPA, jamás en el dominio.

El DDL completo se entrega en `db/schema.sql` para que una persona lo ejecute. Sin Flyway.

---

## Trazabilidad con los requisitos

| Elemento | Requisitos que cubre |
| --- | --- |
| `Reconciliation.start()` | FR-001, FR-002 |
| `RegisterCoreTransactionCommand` + `findByOperationReference` | FR-003, FR-004 |
| `Amount.subtract()` | FR-005 |
| `Amount.isWithinTolerance()` | FR-006, FR-007, FR-008 |
| `difference` como atributo | FR-009 |
| `ReconciliationStatus` + matriz | FR-010, FR-011, FR-012 |
| `ReconciliationEventPublisher` | FR-013, FR-014, FR-014b, FR-015 |
| `findByOperationReference` | FR-016, FR-017 |
| `UNIQUE` + `ProcessedOperationStore` | FR-018, FR-019 |
| `isResolved()` comprobado antes | FR-020 |
| `Optional` vacío del repositorio | FR-021 |
| Validación en `Amount` y `Currency` | FR-022 |
