# Phase 1 — Quickstart: validación end-to-end

**Fecha**: 2026-09-20 | **Plan**: [plan.md](./plan.md)

Guía para arrancar el servicio y comprobar que la conciliación funciona de punta a punta.
No contiene código de implementación: eso pertenece a `tasks.md` y a la fase de
implementación.

---

## Prerequisitos

La infraestructura ya existe y la administra el equipo. Este servicio no la provisiona.

| Recurso | Estado | Quién lo prepara |
| --- | --- | --- |
| PostgreSQL en Supabase | Creado | Equipo |
| Tabla `reconciliations` | **Pendiente**: ejecutar `db/schema.sql` | Una persona, a mano |
| Kafka en Confluent Cloud | Creado | Equipo |
| Los 6 topics (2 entrada, 2 DLQ, 2 salida) | Creados. Sus nombres se inyectan por entorno | Equipo |
| Schema Registry de Confluent | Creado | Equipo |
| Los 4 esquemas Avro registrados | **Pendiente**: el auto-registro está desactivado | Equipo, desde `src/main/avro` |
| Redis local | Configurado | Equipo |

### Sin bloqueantes pendientes

Los nombres de los seis topics ya no bloquean nada en el repositorio: llegan por variable de
entorno como el resto de la configuración. Quien despliega los rellena en su `.env` con los
nombres reales que el equipo haya creado.

La configuración técnica está resuelta y verificada en [research.md](./research.md),
incluidas la autenticación SASL del bróker (R-09) y la del Schema Registry de Confluent
Cloud (R-10).

### Dos trampas que verás si algo se copió mal

- **La versión del driver PostgreSQL debe ser 42.7.9 o superior.** La 42.7.8 —y solo ella—
  reintrodujo pinning de virtual threads; el PR #3849 lo corrigió en 42.7.9. Comprobar qué
  resuelve el BOM antes de tocar nada:

  ```bash
  mvn dependency:tree -Dincludes=org.postgresql:postgresql
  ```

  Si aparece `VirtualThread$VThreadContinuation.onPinned` con `reason:MONITOR` atravesando
  `org.postgresql` en el log, es esto. Ver R-13.
- **`enableDecimalLogicalType` debe estar en `true`** en el `avro-maven-plugin`. Con el
  default (`false`), los montos llegan como `ByteBuffer` en vez de `BigDecimal` y el mapper
  no compila contra `Amount`. Ver R-12.

---

## Configuración local

Crear un `.env` en la raíz a partir de `.env.example`. Nunca se versiona.

```bash
cp .env.example .env
# Rellenar los 9 valores. Si falta cualquiera, la aplicación falla al arrancar:
# la sintaxis ${NOMBRE} no lleva valor por defecto, a propósito.
```

Variables requeridas, las quince y ninguna más:

```text
# Conexión y credenciales
DB_URL DB_USERNAME DB_PASSWORD
REDIS_HOSTS
KAFKA_BOOTSTRAP_SERVERS KAFKA_API_KEY KAFKA_API_SECRET
SCHEMA_REGISTRY_URL SCHEMA_REGISTRY_API_KEY SCHEMA_REGISTRY_API_SECRET

# Nombres de topic
KAFKA_TOPIC_PAYMENTS_IN            KAFKA_TOPIC_PAYMENTS_DLQ
KAFKA_TOPIC_CORE_TRANSACTIONS_IN   KAFKA_TOPIC_CORE_TRANSACTIONS_DLQ
KAFKA_TOPIC_RESULTS_OUT            KAFKA_TOPIC_DISCREPANCIES_OUT
```

Las credenciales del Schema Registry son **distintas** de las del clúster: Confluent Cloud
emite dos pares de API key/secret independientes y reutilizar los del bróker da 401 (R-10).

`DB_URL` debe apuntar a la conexión directa de Supabase o a su pooler **en modo sesión**. El
pooler en modo transacción no sirve: rompe los prepared statements de Hibernate.

---

## Preparar la base de datos

```bash
# Lo ejecuta una persona contra Supabase. No hay Flyway ni migración automática.
psql "$DB_URL" -f db/schema.sql
```

Comprobar que el índice único quedó creado — es la garantía real de idempotencia, no un
detalle:

```sql
SELECT indexname, indexdef FROM pg_indexes
WHERE tablename = 'reconciliations';
-- Debe aparecer un UNIQUE sobre operation_reference
```

---

## Arrancar

```bash
mvn quarkus:dev          # hot reload, Dev Services desactivado
```

Señales de que el arranque es correcto:

- La aplicación levanta sin intentar crear contenedores. Si aparece un contenedor,
  `quarkus.devservices.enabled=false` no se aplicó.
- No hay ninguna sentencia DDL en el log. Si Hibernate emite `create table`,
  `quarkus.hibernate-orm.schema-management.strategy` no está en `none` (ver R-01: el default
  se sobrescribe a `drop-and-create` cuando Dev Services está activo).
- Los cuatro canales aparecen conectados y ningún esquema se registra al arrancar: el
  auto-registro está desactivado a propósito.

### Comprobar que los virtual threads no se están bloqueando

El modelo de concurrencia entero depende de que ningún hilo virtual quede clavado a su
carrier. En Java 21 no hay red de seguridad del runtime —JEP 491 llegó en Java 24—, así que
conviene verificarlo una vez de forma empírica:

```bash
mvn quarkus:dev -Djdk.tracePinnedThreads=full
```

Enviar unos cuantos mensajes y revisar el log: **no debe aparecer ninguna traza `onPinned`
que atraviese `org.postgresql`**. Si aparece, el driver resuelto es la 42.7.8 y hay que
subirlo (R-13). Ni el BOM ni el `pom.xml` garantizan esto por sí solos.

---

## Escenarios de validación

Cada escenario se corresponde con un criterio de aceptación de [spec.md](./spec.md). Los
mensajes de entrada se publican en los topics con la herramienta que use el equipo; los
importes viajan como `bytes` con `logicalType decimal`, no como número.

### 1. Conciliación conforme — el camino feliz

Publicar en el canal de pagos un `PaymentExecuted` con `operationReference = OP-1` y
`amount = 100.00 PEN`.

```bash
curl -s http://localhost:8080/v1/reconciliations/OP-1 | jq
# Esperado: status PENDING, sin coreAmount ni difference, y NADA publicado
```

Publicar ahora un `CoreTransactionReported` con `OP-1` y `100.00 PEN`.

```bash
curl -s http://localhost:8080/v1/reconciliations/OP-1 | jq
# Esperado: status MATCHED, difference "0.00"
# Y un ReconciliationMatched en el canal de conformes
```

### 2. La tabla de tolerancia completa

Repetir con una referencia distinta por fila. Es la validación de FR-006 y FR-007, y el
núcleo de SC-002.

| Referencia | Pago | Movimiento | Estado esperado | Diferencia | Canal |
| --- | --- | --- | --- | --- | --- |
| OP-2 | 100.00 | 100.00 | MATCHED | 0.00 | conformes |
| OP-3 | 100.00 | 99.50 | MATCHED | 0.50 | conformes |
| OP-4 | 100.00 | 99.00 | MATCHED | 1.00 | conformes |
| OP-5 | 100.00 | 98.99 | MISMATCHED | 1.01 | discrepancias |
| OP-6 | 100.00 | 95.00 | MISMATCHED | 5.00 | discrepancias |
| OP-7 | 100.00 | 100.50 | MISMATCHED | -0.50 | discrepancias |

OP-4 y OP-5 son el par que importa: separan el límite inclusivo de PEN 1.00 del primer
céntimo fuera de tolerancia. OP-7 comprueba que **toda** diferencia negativa es MISMATCHED,
por pequeña que sea.

### 3. Idempotencia del pago

Publicar dos veces el mismo `PaymentExecuted` con `OP-8`.

```bash
curl -s http://localhost:8080/v1/reconciliations/OP-8 | jq
# Esperado: una sola conciliación, en PENDING
```

```sql
SELECT count(*) FROM reconciliations WHERE operation_reference = 'OP-8';
-- Esperado: 1
```

En el log debe aparecer un INFO de descarte por idempotencia, **no un ERROR**: un duplicado
rechazado es procesamiento correcto (FR-019). Si el mensaje acabó en la DLQ, el caso de uso
está tratando el duplicado como fallo.

Para comprobar que la garantía real es la base y no Redis, repetir con Redis detenido: el
resultado debe ser el mismo, rechazado por el `UNIQUE`.

### 4. Movimiento sobre una conciliación ya resuelta

Sobre `OP-2`, ya MATCHED, publicar otro `CoreTransactionReported`.

Esperado: la conciliación **no cambia**, no se publica nada, y aparece un WARN. No debe
lanzarse `IllegalStateTransitionException` — el caso de uso comprueba el estado antes de
intentar la transición (FR-020). Si la excepción aparece en el log, el orden de comprobación
está invertido y el mensaje acabará en la DLQ en lugar de descartarse.

### 5. Movimiento huérfano

Publicar un `CoreTransactionReported` con `OP-999`, sin pago previo.

Esperado: descarte con WARN, nada publicado, ninguna conciliación creada.

### 6. Entradas inválidas

| Caso | Entrada | Esperado |
| --- | --- | --- |
| Monto cero | `PaymentExecuted` con `0.00 PEN` | Sin conciliación; mensaje en la DLQ del canal de pagos |
| Monto negativo | `PaymentExecuted` con `-5.00 PEN` | Igual |
| Otra moneda | `PaymentExecuted` con `100.00 USD` | Igual |

Comprobar que el registro en la DLQ trae las cabeceras `dead-letter-reason` y
`dead-letter-cause`, que añade el conector (ver R-03).

### 7. Consulta inexistente

```bash
curl -s -i http://localhost:8080/v1/reconciliations/OP-404
# Esperado: 404, Content-Type: application/problem+json, cuerpo RFC 7807
```

---

## Suite de tests

```bash
mvn verify
```

La puerta es bloqueante y en este orden: `spotless:check` falla si el formato no está
aplicado, y Checkstyle falla con `severity=error`, incluido el control de imports por
paquete. El build **nunca** modifica archivos; `spotless:apply` es una acción local previa.

```bash
mvn spotless:apply       # antes de commitear
```

Lo que la suite debe cubrir, según la constitución XIII:

- Matriz completa de transiciones de estado: 9 combinaciones, 2 permitidas, 7 rechazadas.
- Validación en construcción de cada Value Object.
- `Amount`: `120.0` y `120.00` son iguales; comparar PEN con USD falla.
- Idempotencia por consumer, con `InMemoryConnector`.
- Los cuatro mappers, de ida y vuelta.

Sin Testcontainers y sin levantar contenedores desde el test.

Los tests que corren contra la infraestructura real comparten recursos con otras personas:
deben usar un prefijo identificable en las referencias de operación y limpiar lo que crean.

---

## Limitaciones que verás y no son defectos

1. **Un movimiento que llega antes que su pago se descarta**, y el pago queda PENDING para
   siempre. No hay reintento ni reconciliación posterior.
2. **Si el servicio cae entre el commit y la publicación, el evento se pierde.** La
   conciliación queda correctamente resuelta en base, pero sin resultado publicado. No hay
   outbox y no hay recuperación.

Ambas están documentadas en [spec.md](./spec.md) y deben figurar en el README.
