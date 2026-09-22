# ms-conciliaciones

Microservicio de conciliación de pagos. Verifica que cada pago reportado tenga su movimiento
del core, compara importes contra una tolerancia fija y publica el resultado.

**Es una demo técnica, no un sistema productivo.** Las limitaciones de abajo son decisiones
conscientes, no defectos pendientes.

## Arranque

```bash
cp .env.example .env     # rellenar las 15 variables
psql "$DB_URL" -f db/schema.sql
mvn quarkus:dev
```

## Variables de entorno

Ninguna tiene valor por defecto. Si falta una, la aplicación **falla al arrancar**, que es lo
que queremos: es preferible a arrancar leyendo del sitio equivocado.

### Conexión

| Variable | Notas |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Supabase. Conexión directa o pooler **en modo sesión**; el modo transacción rompe los prepared statements de Hibernate |
| `REDIS_HOSTS` | Instancia local |
| `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_API_KEY`, `KAFKA_API_SECRET` | Confluent Cloud |
| `SCHEMA_REGISTRY_URL`, `SCHEMA_REGISTRY_API_KEY`, `SCHEMA_REGISTRY_API_SECRET` | **Credenciales distintas de las del clúster.** Reutilizar las del bróker devuelve 401 |

### Topics

Los nombres no viven en el repositorio. El equipo es dueño de los topics y de sus nombres;
aquí solo se declara qué rol cumple cada uno, para que se pueda verificar que existen.

| Variable | Rol |
| --- | --- |
| `KAFKA_TOPIC_PAYMENTS_IN` | Consume pagos reportados |
| `KAFKA_TOPIC_PAYMENTS_DLQ` | Descarte del canal de pagos |
| `KAFKA_TOPIC_CORE_TRANSACTIONS_IN` | Consume movimientos del core |
| `KAFKA_TOPIC_CORE_TRANSACTIONS_DLQ` | Descarte del canal de movimientos |
| `KAFKA_TOPIC_RESULTS_OUT` | Publica conciliaciones conformes |
| `KAFKA_TOPIC_DISCREPANCIES_OUT` | Publica discrepancias |

Los cuatro esquemas Avro de `src/main/avro` los registra el equipo: el auto-registro está
desactivado a propósito.

## Cómo concilia

1. Llega un pago reportado → se crea una conciliación en `PENDING`. No se publica nada.
2. Llega el movimiento del core → `diferencia = monto del pago − monto del movimiento`.
3. Diferencia cero, o positiva y **≤ PEN 1.00** → `MATCHED`, publicado en conformes.
4. Diferencia positiva **> tolerancia**, o **negativa de cualquier magnitud** → `MISMATCHED`,
   publicado en discrepancias.

`MATCHED` y `MISMATCHED` son terminales.

## Consulta

```
GET /v1/reconciliations/{operationReference}
  200 → detalle
  404 → application/problem+json (RFC 7807)
```

Sin autenticación: se delega al perímetro.

## Limitaciones conocidas

**1. Un movimiento que llega antes que su pago se pierde.** El orden de entrega entre canales
distintos no está garantizado. En ese caso el movimiento se descarta con una advertencia y el
pago, al llegar después, queda `PENDING` para siempre. No hay detección de huérfanos ni
reintento.

**2. Un fallo entre el commit y la publicación pierde el evento.** La publicación ocurre
después de confirmar la persistencia, **sin patrón outbox**. Si el servicio cae en esa
ventana, la conciliación queda correctamente resuelta en base de datos pero su resultado nunca
se publica, y no existe mecanismo de recuperación. Por eso el contrato promete publicar *como
máximo una vez*, no *exactamente una vez*: la duplicación sí se evita, la pérdida se asume.

**3. La ausencia de concurrencia sobre una misma operación es un supuesto, no una garantía
propia.** El servicio resuelve en dos pasos —consultar estado y transicionar—. Que dos
movimientos de la misma referencia no se procesen a la vez depende de que los sistemas origen
identifiquen sus mensajes por referencia de operación. Si alguno dejara de hacerlo, podría
publicarse un resultado duplicado sin que este servicio lo detecte.

## Fuera de alcance

Detección de huérfanos, fecha contable, hora de corte, archivo de cierre, otras monedas,
listado o búsqueda, historial de transiciones, reintento manual, notificaciones, interfaz
gráfica, autenticación, métricas y trazas.

## Calidad

```bash
mvn spotless:apply     # antes de commitear; el build nunca modifica archivos
mvn verify             # Checkstyle (severity=error) + Spotless + tests
```

Checkstyle hace cumplir la frontera hexagonal con control de imports: `domain` no importa
frameworks ni `infrastructure`, y `application` no importa `infrastructure`. No es disciplina,
es una puerta del build.
