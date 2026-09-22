<!--
SYNC IMPACT REPORT — enmienda vigente
=====================================
Version change: 3.0.0 → 3.1.0
Bump rationale: MINOR. Se amplía materialmente una guía sin revertir nada: la lista cerrada
                de variables de entorno del Principio IV pasa de 9 a 15 entradas, al decidir
                que los nombres de los topics también se inyectan por entorno en lugar de
                quedar literales en application.yaml.

Modified principles:
  - IV   La lista cerrada incorpora seis variables de topic:
         KAFKA_TOPIC_PAYMENTS_IN, KAFKA_TOPIC_PAYMENTS_DLQ,
         KAFKA_TOPIC_CORE_TRANSACTIONS_IN, KAFKA_TOPIC_CORE_TRANSACTIONS_DLQ,
         KAFKA_TOPIC_RESULTS_OUT, KAFKA_TOPIC_DISCREPANCIES_OUT.
         Se aclara que la regla de ${NOMBRE} sin valor por defecto también las cubre: un
         nombre de topic ausente debe impedir el arranque, no degradarse a un default.
  - III  La obligación de listar los topics en el README pasa a listar las variables que los
         nombran, porque el nombre literal ya no vive en el repositorio.

Sin cambios: I, II, V, VI, VII, VIII, IX, X, XI, XII, XIII.
Added sections: ninguna.   Removed sections: ninguna.
Deferred TODOs: ninguno.

Nota: bloque temporal de revisión, no gobernanza. Elimínalo antes de commitear.
-->

<!--
SYNC IMPACT REPORT — enmienda anterior (histórico)
==================================================
Version change: 2.0.0 → 3.0.0
Bump rationale: MAJOR. Enmienda por reemplazo total, con instrucción explícita de no
                fusionar con la versión anterior. Retira gobernanza que la v2.0.0
                imponía, no solo la reformula.

Modified principles (ninguno renombrado; los trece conservan nombre y numeral):
  - II   Sale el driver JDBC de PostgreSQL de la lista del stack. Se recorta la
         justificación sobre versiones, renombres y deprecaciones.
  - III  La compatibilidad de esquemas ya no se nombra como BACKWARD: solo se dice que la
         configura el equipo. Se recorta el detalle de particiones/réplicas y el motivo por
         el que se descarta el pooler en modo transacción.
  - IV   La configuración de negocio queda reducida a DOS entradas: tolerancia y TTL de
         idempotencia. Desaparecen la zona horaria de negocio (America/Lima) y la hora de
         corte. Desaparece también el comentario sobre DB_URL en .env.example.
  - VIII La compatibilidad BACKWARD de los contratos Avro deja de declararse.
         "Identificador de la operación" pasa a "referencia de operación".
  - IX   Se retira la verificación obligatoria con Context7 del pinning de virtual threads
         en el driver JDBC de PostgreSQL.
  - X    El bloque de Tiempo se reduce a "Instant para fechas y horas". Desaparecen
         LocalDate como fecha contable, el cálculo con zona horaria de negocio, el veto a
         ZoneId.systemDefault() y el Clock inyectado por puerto.
  - XI   Glosario reescrito: de 13 entradas a 8. Se retiran Discrepancia→Discrepancy,
         Partida conciliatoria→OpenDiscrepancy, Archivo de cierre→SettlementFile, Número de
         operación→OperationId, Fecha contable→BusinessDate, Hora de corte→Cutoff y
         Extorno→Reversal. Entran Referencia de operación→OperationReference y
         Diferencia→AmountDifference. Se retira la exención de Javadoc en infraestructura y
         getters.
  - XIII Desaparece el test de asignación de fecha contable con Clock fijo.

Sin cambios de fondo: I, V, VI, VII, XII.

Added sections: ninguna.
Removed sections: ninguna. Las dos secciones de apoyo se reconstruyeron únicamente a
  partir del texto nuevo, sin arrastrar contenido de la v2.0.0.

Deferred TODOs: ninguno. Todos los placeholders quedaron resueltos.

Nota: este bloque es material de revisión temporal de la enmienda, no contenido de
gobernanza. Elimínalo antes de commitear la constitución enmendada.
-->

# Constitución del Microservicio de Conciliación de Pagos

Microservicio de conciliación de pagos en Quarkus. Es una DEMO técnica, no un sistema
productivo. Estos principios buscan un diseño correcto y legible, no cobertura operativa
completa.

## Core Principles

### I. No Sobreextenderse

Se implementa lo que el spec pide y nada más. PROHIBIDO agregar funcionalidad, capas,
abstracciones o configuraciones no solicitadas.

Ante la duda entre una solución simple y una sofisticada, se elige la simple.

Si una decisión no está en la especificación ni en el plan, se PREGUNTA antes de
implementarla; nunca se asume.

**Rationale**: en una demo, cada capa extra es superficie de error sin valor demostrativo.
El alcance lo fija el spec, no la iniciativa del implementador.

### II. Documentación Verificada Antes de Escribir Código

Context7 está instalado en el entorno, tanto como skill como MCP.

Antes de usar cualquier tecnología, librería, extensión, anotación o propiedad de
configuración, se DEBE consultar su documentación vigente con Context7. Sin excepciones y
sin que haga falta pedirlo en cada prompt.

PROHIBIDO escribir configuración o llamadas a API desde el conocimiento entrenado del
agente. Una propiedad inventada falla en arranque o se ignora en silencio, y una firma
obsoleta falla en compilación.

Si Context7 no cubre el caso, se investiga en la documentación oficial del proyecto antes de
escribir el código.

Si algo no se puede verificar por ninguna de las dos vías, se DEJA anotado como pendiente de
verificación y se REPORTA. Nunca se adivina.

Aplica a todo el stack: Quarkus y sus extensiones, Hibernate ORM y Panache, SmallRye
Reactive Messaging, Avro y su plugin Maven, el serde Avro de Confluent Schema Registry, el
cliente Redis, MapStruct, quarkus-openapi-generator, Checkstyle, Spotless y JUnit.

### III. Infraestructura Gestionada por el Equipo

La infraestructura ya existe y la configura una persona. El agente NO la provisiona, NO la
configura y NO genera scripts para crearla.

- **Kafka y Schema Registry**: Confluent Cloud, ya creado. Los topics ya existen. El código
  solo referencia nombres de topic; nunca declara particiones, réplicas ni retención.
- Se usa exclusivamente el serde Avro de Confluent. PROHIBIDO Apicurio o cualquier otro
  registry o serde.
- La compatibilidad de esquemas la configura el equipo en el Schema Registry. El productor
  tiene desactivado el auto-registro de esquemas: el equipo registra los esquemas.
- **Base de datos**: PostgreSQL en Supabase, ya creada. La conexión es directa o por el
  pooler en modo sesión, nunca en modo transacción.
- **Redis**: instancia local, ya configurada.
- PROHIBIDO Flyway y cualquier herramienta de migración. El DDL se entrega en
  `db/schema.sql` para que una persona lo ejecute.
- Hibernate nunca crea, valida ni altera el esquema. La propiedad de Quarkus que lo
  desactiva se verifica con Context7.
- PROHIBIDO generar docker-compose, manifiestos de Kubernetes, Terraform o scripts de
  creación de topics.
- Quarkus Dev Services desactivado: nunca se levantan contenedores.
- Responsabilidad del agente respecto a infraestructura:
  - declarar las variables de entorno en `.env.example`
  - listar en el `README` las variables de entorno que nombran los topics que usa, DLQ
    incluidos, con el rol de cada una, para que el equipo verifique que los topics
    correspondientes existen. Los nombres literales no viven en el repositorio

### IV. Configuración y Secretos

`application.yaml` con la extensión `quarkus-config-yaml`.

Todo dato de conexión es variable de entorno en `MAYUSCULAS_CON_GUION_BAJO`, con sintaxis
`${NOMBRE}` sin valor por defecto: si falta, la aplicación falla al arrancar.

PROHIBIDA cualquier credencial, URL de conexión o token literal en el repositorio.
`.env.example` lleva esos nombres con valores vacíos. El `.env` real está en `.gitignore`.
El inventario cerrado de variables se define en *Restricciones Técnicas y Artefactos del
Repositorio*.

Los **nombres de los topics** también son variables de entorno, con la misma regla: sin valor
por defecto. Un nombre de topic ausente DEBE impedir el arranque. Degradarlo a un default
sería peor que fallar: la aplicación arrancaría consumiendo o publicando en un topic
equivocado, o en uno inexistente que Confluent Cloud no auto-crea, y el fallo aparecería como
silencio en vez de como error.

Configuración de negocio con `@ConfigMapping` en una interfaz tipada que vive en
`infrastructure`. Contiene solo:

- tolerancia (monto fijo con moneda)
- TTL del filtro de idempotencia en Redis

El valor de tolerancia se define en la especificación funcional.

La interfaz `@ConfigMapping` nunca llega al dominio: sus valores entran convertidos en Value
Objects.

### V. Arquitectura Hexagonal

El dominio no conoce frameworks. Ninguna clase en `domain` importa `jakarta.persistence`,
`io.smallrye`, `io.quarkus`, `org.apache.kafka`, `org.apache.avro` ni paquetes de
infraestructura.

Estructura de paquetes obligatoria:

```
domain/model
domain/model/vo
domain/port/in
domain/port/out
application/service
infrastructure/adapter/in
infrastructure/adapter/out
```

`application` solo depende de `domain`.

La regla se verifica con el control de imports de Checkstyle (ver XII).

### VI. Dependency Inversion

El dominio define los puertos como interfaces; la infraestructura los implementa. Las
dependencias apuntan siempre hacia el dominio. Los casos de uso reciben puertos, no
implementaciones concretas.

### VII. Modelado Táctico DDD

**Aggregate Root**

- Único punto de entrada; nada externo referencia sus entidades internas.
- Sin setters públicos. El estado cambia con métodos de negocio con nombre de intención.
- Constructor privado. Factory estáticos: `start()` para uno nuevo (valida invariantes) y
  `reconstitute()` para rehidratar (solo lo usa el mapper de infraestructura).

**Máquina de estados**

- El enum de estado declara qué transiciones son válidas desde cada estado.
- Una transición no permitida lanza `IllegalStateTransitionException`; jamás se ignora en
  silencio.
- El estado se persiste como `String`, nunca como ordinal.
- Los estados concretos se definen en la especificación funcional.

**Value Objects**

- Todo concepto sin identidad propia es un `record` inmutable.
- PROHIBIDO `String` o `BigDecimal` crudos en firmas del dominio.
- El Value Object valida en su construcción: si es inválido, no existe.
- Montos en `BigDecimal`, jamás `double` ni `float`, siempre con moneda.
- Montos normalizados a escala 2 con `RoundingMode` explícito.
- Comparación de montos con `compareTo`, nunca `BigDecimal.equals`.
- Comparar montos de monedas distintas lanza una excepción de dominio.

**Entidades y repositorios**

- Una entidad del dominio NUNCA es una entidad JPA. Mapper explícito entre ambas.
- Un repositorio por aggregate root, declarado como puerto de salida.
- PROHIBIDO Panache Active Record. Se usa `PanacheRepository` sobre entidades JPA en
  `infrastructure`.

### VIII. Mensajería

- Todo consumer es idempotente por referencia de operación.
- La garantía es una restricción `UNIQUE` en base de datos sobre la referencia de operación.
  Redis es solo un filtro rápido previo con TTL. Un duplicado rechazado se trata como
  procesado con éxito, no como error.
- Todo consumer declara `dead-letter-queue` como `failure-strategy`, con el nombre del topic
  de DLQ declarado explícitamente en configuración. PROHIBIDO el nombre por defecto del
  framework.
- Offsets commiteados después de procesar, nunca antes.
- Contratos Avro. Los campos nuevos siempre con `default`.
- El esquema Avro no es el modelo de dominio: el adaptador de entrada lo traduce a Value
  Objects.
- La publicación a Kafka ocurre después de confirmar la transacción de base de datos. Sin
  outbox: el riesgo de perder el evento se documenta en el `README`.

### IX. Concurrencia

- Modelo bloqueante con virtual threads, no reactivo.
- `@RunOnVirtualThread` en los consumers Kafka y en los endpoints REST.
- PROHIBIDO mezclar Mutiny o Panache reactive con virtual threads.
- PROHIBIDO `synchronized` en secciones con I/O: usar `ReentrantLock`.

### X. Java 21 Moderno

- `record` para Value Objects, DTOs y comandos.
- `sealed interface` con pattern matching for switch, sin `default` sobre un tipo sealed.
- PROHIBIDO `instanceof` seguido de cast.
- `var` donde el tipo es evidente; `Stream.toList()`; text blocks para SQL.
- `Optional` solo como retorno de puertos. PROHIBIDO como parámetro o campo, y PROHIBIDO
  `isPresent()` seguido de `get()`.
- PROHIBIDOS: `null` como retorno del dominio, `java.util.Date`, colecciones mutables
  expuestas desde el agregado y excepciones checked en el dominio.
- `Instant` para fechas y horas.
- Lombok PROHIBIDO en `domain` y `application`. Solo en entidades JPA, y solo `@Getter`,
  `@Setter` y `@NoArgsConstructor(access = PROTECTED)`. PROHIBIDOS `@Data`, `@Builder` y
  `@EqualsAndHashCode`.

### XI. Idioma

- TODO el código fuente en inglés: clases, métodos, variables, paquetes, mensajes de
  excepción, tests y comentarios.
- Los topics de Kafka y las columnas de base de datos mantienen la nomenclatura de negocio
  existente.
- `docs/glossary.md` con la equivalencia normativa recogida en *Restricciones Técnicas y
  Artefactos del Repositorio*.
- Javadoc en clases del dominio y puertos: invariantes, validaciones y contrato. PROHIBIDO
  el Javadoc que solo repite el nombre del método.

### XII. Calidad

- Checkstyle en `mvn verify` con `severity=error`. Solo reglas semánticas, nunca formato:
  - complejidad ciclomática máx. 10
  - métodos máx. 40 líneas
  - parámetros máx. 5
  - línea máx. 140
  - imports sin estrella ni sin usar
  - control de imports: `domain` no importa frameworks ni `infrastructure`; `application` no
    importa `infrastructure`
- Spotless para el formato: `spotless:apply` en local, `spotless:check` en `mvn verify`. El
  build nunca modifica archivos.
- Sin umbral de cobertura: los tests que existan deben ser significativos.

### XIII. Testing

- Dominio con JUnit puro, sin `@QuarkusTest` ni mocks de infraestructura.
- Test de la matriz completa de transiciones de estado.
- Test de validación en construcción de cada Value Object.
- Test del monto: `120.0` y `120.00` son iguales; monedas distintas fallan.
- Test de idempotencia por consumer: el mismo evento dos veces produce un solo efecto.
- Adaptadores con la infraestructura real o con `InMemoryConnector` de SmallRye. Sin
  contenedores.
- Los tests contra infraestructura real usan datos con prefijo identificable y limpian lo
  que crean.

## Restricciones Técnicas y Artefactos del Repositorio

**Variables de entorno — lista cerrada.** Estas quince y ninguna más:

*Conexión y credenciales*

```
DB_URL, DB_USERNAME, DB_PASSWORD
REDIS_HOSTS
KAFKA_BOOTSTRAP_SERVERS, KAFKA_API_KEY, KAFKA_API_SECRET
SCHEMA_REGISTRY_URL, SCHEMA_REGISTRY_API_KEY, SCHEMA_REGISTRY_API_SECRET
```

*Nombres de topic*

```
KAFKA_TOPIC_PAYMENTS_IN, KAFKA_TOPIC_PAYMENTS_DLQ
KAFKA_TOPIC_CORE_TRANSACTIONS_IN, KAFKA_TOPIC_CORE_TRANSACTIONS_DLQ
KAFKA_TOPIC_RESULTS_OUT, KAFKA_TOPIC_DISCREPANCIES_OUT
```

Las de topic no son secretos, pero siguen la misma regla de `${NOMBRE}` sin default por la
razón de arriba. Que estén en `.env.example` con valor vacío es deliberado: obliga a
rellenarlas con los nombres reales que el equipo haya creado.

La configuración de negocio del Principio IV no viaja en estas variables: vive en la
interfaz `@ConfigMapping`.

**Glosario de negocio obligatorio** (`docs/glossary.md`):

| Término de negocio (es) | Clase (en)         |
| ----------------------- | ------------------ |
| Conciliación            | Reconciliation     |
| Pago reportado          | ReportedPayment    |
| Movimiento del core     | CoreTransaction    |
| Referencia de operación | OperationReference |
| Monto                   | Amount             |
| Moneda                  | Currency           |
| Diferencia              | AmountDifference   |
| Tolerancia              | Tolerance          |

**Artefactos que el repositorio DEBE contener:**

| Artefacto          | Contenido                                                             |
| ------------------ | --------------------------------------------------------------------- |
| `.env.example`     | Los nombres de la lista cerrada, con valores vacíos                    |
| `db/schema.sql`    | El DDL completo, incluida la restricción `UNIQUE` sobre la referencia de operación |
| `docs/glossary.md` | La tabla de equivalencias anterior                                     |
| `README`           | Las variables que nombran los topics usados, DLQ incluidos, con el rol de cada una, y la ausencia de outbox con su riesgo |

**Artefactos y dependencias PROHIBIDOS:** Apicurio y cualquier registry o serde que no sea
el de Confluent, docker-compose, manifiestos de Kubernetes, Terraform, scripts de creación
de topics, migraciones Flyway, Quarkus Dev Services activo y cualquier archivo con una
credencial, URL de conexión o token literal.

## Flujo de Trabajo y Puertas de Calidad

**Antes de escribir código**, en este orden:

1. Confirmar que lo pedido está en el spec o en el plan. Si no está, PREGUNTAR (Principio I).
2. Consultar Context7 para cada tecnología, propiedad o anotación implicada (Principio II).
3. Si Context7 no cubre el caso, consultar la documentación oficial del proyecto.
4. Si sigue sin poder verificarse, anotarlo como pendiente de verificación y reportarlo.
   Nunca se adivina.

Una verificación queda nombrada explícitamente como obligatoria: la propiedad de Quarkus que
desactiva la gestión de esquema de Hibernate (Principio III).

**Puerta de build.** `mvn verify` es la puerta única y es bloqueante: `spotless:check` falla
si el formato no está aplicado y Checkstyle falla con `severity=error`, incluido el control
de imports por paquete. El build nunca modifica archivos: `spotless:apply` es una acción
local previa.

**Revisión de cambios.** Toda revisión verifica, como mínimo:

- `domain` no importa ningún paquete de framework y `application` no importa
  `infrastructure` (Principio V; el build ya lo bloquea).
- Ninguna credencial literal ni variable fuera de la lista cerrada (Principio IV).
- No se añadió infraestructura, migraciones, Dev Services ni funcionalidad no solicitada
  (Principios I, III).
- Los consumers nuevos traen su test de idempotencia, su DLQ con nombre explícito y su topic
  listado en el `README` (Principios III, VIII, XIII).

## Governance

Esta constitución prevalece sobre cualquier otra práctica, convención o preferencia. Ante un
conflicto entre esta constitución y un spec, un plan o una sugerencia del agente, prevalece
la constitución y el conflicto se reporta explícitamente.

**Procedimiento de enmienda.** Una enmienda requiere: (a) la propuesta escrita del cambio y
su motivo, (b) aprobación explícita del responsable del proyecto, y (c) la actualización de
esta constitución mediante `/speckit-constitution`, con su Sync Impact Report. El agente no
enmienda esta constitución por iniciativa propia.

**Política de versionado.** Versionado semántico sobre el documento:

- **MAJOR**: se elimina o redefine un principio de forma incompatible con el trabajo previo.
- **MINOR**: se añade un principio o una sección, o se amplía materialmente una guía.
- **PATCH**: aclaraciones, redacción, erratas y refinamientos sin cambio semántico.

**Revisión de cumplimiento.** El cumplimiento se verifica en cada revisión de cambios según
la lista de *Flujo de Trabajo y Puertas de Calidad*. Una desviación no se implementa "y se
documenta después": se plantea antes y se resuelve con una enmienda o con un cambio de
diseño que cumpla.

**Guía en tiempo de desarrollo.** Los specs y planes generados por Spec Kit bajo `specs/`
son la fuente de alcance; esta constitución es la fuente de restricciones. Una demo con
alcance recortado sigue siendo conforme; una demo que viola una restricción no lo es.

**Version**: 3.1.0 | **Ratified**: 2026-09-20 | **Last Amended**: 2026-09-20
