# Phase 0 — Research: Conciliación de Pagos

**Fecha**: 2026-09-20
**Método**: Context7 por las dos vías exigidas — skill (`npx ctx7@latest`) y MCP
(`resolve-library-id` + `query-docs`). Cuando ambas devolvieron resultado se contrastaron
antes de fijar la decisión. Lo que Context7 no cubría se investigó después en código fuente y
documentación oficial: son R-08 a R-13 y D-01, en la segunda mitad del documento. **No queda
nada sin verificar, y nada se adivinó.**

---

## R-01 — Desactivar la gestión de esquema de Hibernate

**Decisión**: `quarkus.hibernate-orm.schema-management.strategy=none`, acompañado de
`quarkus.hibernate-orm.sql-load-script=no-file`.

**Verificado por**: MCP sobre `/quarkusio/quarkus` y skill sobre `/websites/quarkus_io_guides`.
Ambas coinciden.

**Rationale**: dos hallazgos que cambian lo que habría escrito de memoria.

1. `quarkus.hibernate-orm.database.generation` está **deprecada desde Quarkus 3.22** con
   `forRemoval=true`. El Javadoc de `HibernateOrmRuntimeConfigPersistenceUnit.generation()`
   remite explícitamente a `schema-management.strategy`. Usar la propiedad antigua habría
   funcionado hoy y roto en la siguiente minor.
2. El default de `schema-management.strategy` ya es `none`, **pero la documentación advierte
   que se sobrescribe automáticamente a `drop-and-create` si Dev Services está en uso** y
   ninguna otra extensión gestiona el esquema. Por eso no basta con confiar en el default:
   hay que fijarlo explícitamente *y* desactivar Dev Services (R-02). La constitución III
   prohíbe que Hibernate cree, valide o altere el esquema, y sin ambas piezas esa prohibición
   no se sostiene.

`sql-load-script=no-file` evita que un `import.sql` presente en el classpath se aplique al
arrancar.

**Alternativas descartadas**: `validate` — la constitución III prohíbe explícitamente que
Hibernate valide contra el esquema, no solo que lo altere.

---

## R-02 — Desactivar Quarkus Dev Services

**Decisión**: `quarkus.devservices.enabled=false`, global.

**Verificado por**: MCP sobre `/quarkusio/quarkus` (`dev-services.adoc` y
`databases-dev-services.adoc`).

**Rationale**: la documentación confirma que la propiedad desactiva globalmente el arranque
automático de servicios, tanto en modo dev como en tests. Es la condición que hace válido el
R-01 y la que cumple la prohibición de levantar contenedores (constitución III y XIII).

---

## R-03 — Dead-letter-queue de los consumers Kafka

**Decisión**: por canal de entrada,
`failure-strategy=dead-letter-queue` + `dead-letter-queue.topic=<nombre explícito>`.

**Verificado por**: MCP sobre `/quarkusio/quarkus` (`kafka.adoc` y
`_includes/smallrye-kafka-incoming.adoc`, que lista las propiedades del conector).

**Rationale**: la documentación confirma los tres atributos del grupo
`dead-letter-queue.*` — `topic`, `key.serializer` y `value.serializer` — y, decisivo, que
**el valor por defecto de `topic` es `dead-letter-topic-$channel`**. Eso confirma la
exigencia del plan de declararlo explícitamente: si no se declara, el conector escribiría a
un topic con nombre autogenerado que no existe en Confluent Cloud, donde los topics los crea
el equipo y no hay auto-creación.

Semántica confirmada de la estrategia: `dead-letter-queue` **commitea el offset** del
registro fallido y escribe el registro en el topic de descarte. Es justo lo que FR-022 exige
para un mensaje inválido: no bloquea el consumo y el mensaje queda disponible para revisión.

Hallazgo adicional: el conector añade cabeceras `dead-letter-reason`, `dead-letter-cause`,
`dead-letter-topic`, `dead-letter-partition` y `dead-letter-offset` al registro descartado.
No hay que construirlas a mano.

**Nota sobre serializers**: Quarkus deduce `dead-letter-queue.[key|value].serializer` del
deserializer del canal cuando detecta configuración de DLQ. No es el mismo nombre de clase:
es su contraparte serializadora.

**Descartado**: `fail` (default) detendría el consumo, violando SC-007. `ignore` commitearía
el offset sin conservar el mensaje, violando FR-022.

---

## R-04 — Filtro de idempotencia en Redis

**Decisión**: `RedisDataSource` bloqueante, grupo `value`, y **`setAndChanged(key, value,
new SetArgs().nx().ex(ttl))`** sobre la clave `dedup:{operationReference}`.

> **Corregida tras resolver P-01.** La decisión original usaba `set(K, V, SetArgs)`. Esa
> firma **devuelve `void`** y habría hecho imposible el filtro. Ver R-08.

**Verificado por**: MCP y skill, ambas sobre la referencia de Redis de Quarkus, más el
código fuente de `SetArgs` vía MCP.

**Confirmado**:

- `@Inject RedisDataSource` es la API bloqueante; `ReactiveRedisDataSource` es la reactiva.
  La constitución IX exige la bloqueante.
- `ds.value(String.class)` devuelve `ValueCommands<String, String>`.
- `SetArgs` expone `nx()`, `ex(seconds)`, `px(millis)`, `xx()`, `keepttl()`. El código fuente
  de `SetArgs.nx()` y `SetArgs.px()` confirma las firmas.
- `setnx(key, value)` existe y solo escribe si la clave no existe, **pero no acepta TTL**.
- `setex(key, seconds, value)` acepta TTL **pero no es condicional**.

**Resuelto en R-08**: Context7 no documentaba el tipo de retorno de `set(K, V, SetArgs)`. La
investigación en el código fuente confirmó que es `void` y que la firma correcta es otra.

**Rationale del diseño, independiente de P-01**: Redis es un filtro de rendimiento, no la
garantía. La garantía es el índice `UNIQUE` sobre `operation_reference` (R-05). Si Redis no
responde o la clave expiró, la base rechaza el duplicado y el caso de uso trata el rechazo
como éxito. Esto sigue la constitución VIII al pie de la letra y hace que P-01, aunque
obligue a cambiar la implementación del filtro, no comprometa la corrección.

---

## R-05 — Claves primarias UUIDv7

**Decisión**: `com.fasterxml.uuid:java-uuid-generator`,
`Generators.timeBasedEpochGenerator()` expuesto como singleton, `.generate()` por agregado.

**Verificado por**: skill (resolución de librería) y MCP sobre
`/cowtowncoder/java-uuid-generator`.

**Confirmado**: `TimeBasedEpochGenerator gen = Generators.timeBasedEpochGenerator();` y
`UUID uuid = gen.generate();` producen UUID v7. El generador por defecto es thread-safe, lo
que importa con virtual threads. Existe la sobrecarga
`timeBasedEpochGenerator(Random, UUIDClock)` con reloj inyectable, útil si algún test
necesitara identificadores deterministas.

**Rationale**: UUIDv7 es monótono por tiempo, lo que evita la fragmentación de índice del
UUIDv4 aleatorio. Generarlo en aplicación permite que `Reconciliation.start()` devuelva un
agregado completo con su identidad antes de tocar la base, requisito del modelado táctico
(constitución VII).

---

## R-06 — Serde Avro contra Confluent Schema Registry

**Decisión**: extensión `quarkus-confluent-registry-avro`, más `quarkus-rest-client`, más
`io.confluent:kafka-avro-serializer` excluyendo `jakarta.ws.rs:jakarta.ws.rs-api`, con el
repositorio Maven de Confluent declarado. URL del registry en
`mp.messaging.connector.smallrye-kafka.schema.registry.url`.

**Verificado por**: skill y MCP sobre la guía `kafka-schema-registry-avro` de Quarkus.

**Hallazgo decisivo**: la documentación afirma que en la implementación de Confluent **el
registro automático de esquemas está activado por defecto**
(`auto.register.schemas`, default `true`), al contrario que en Apicurio. La constitución III
exige que el productor tenga el auto-registro desactivado porque los esquemas los registra el
equipo. Por tanto **hay que ponerlo explícitamente a `false`**; omitirlo significa lo
contrario de lo que la constitución manda.

Nota de propiedades: la URL del registry Confluent va en `schema.registry.url`; el prefijo
`apicurio.registry.*` pertenece al serde de Apicurio, que está prohibido.

---

## R-07 — Mappers con MapStruct

**Decisión**: `@Mapper(componentModel = MappingConstants.ComponentModel.JAKARTA_CDI,
unmappedTargetPolicy = ReportingPolicy.ERROR)`, centralizado en un `@MapperConfig`
compartido por los cuatro mappers.

**Verificado por**: MCP sobre `/mapstruct/mapstruct`.

**Confirmado**: `MappingConstants.ComponentModel.JAKARTA_CDI` existe y vale `"jakarta-cdi"`,
distinto de `CDI` (`"cdi"`, el antiguo javax) y de `JAKARTA` (`"jakarta"`, que es
`jakarta.inject`). Para Quarkus, que usa CDI de Jakarta, el correcto es `JAKARTA_CDI`.
`unmappedTargetPolicy = ReportingPolicy.ERROR` está documentado dentro de `@MapperConfig`,
que es además el mecanismo recomendado para no repetir la configuración en cada mapper.

**Rationale**: un `@MapperConfig` común deja los cuatro mappers con una sola línea de
configuración y garantiza que la política estricta no se olvide en ninguno.

---

---

# Resolución de los pendientes (investigación en fuentes oficiales)

Los seis puntos que Context7 no cubría se resolvieron consultando código fuente y
documentación oficial. Ninguno queda abierto.

---

## R-08 — P-01 · Firma real del SET condicional en Redis

**Fuente**: código fuente de `ValueCommands.java` en el repositorio de Quarkus.

**Hallazgo**: la firma que el plan asumía **no sirve**.

```java
void    set(K key, V value, SetArgs setArgs);            // devuelve void
boolean setnx(K key, V value);                           // boolean, pero sin TTL
boolean setAndChanged(K key, V value, SetArgs setArgs);  // ← boolean Y admite SetArgs
V       setGet(K key, V value, SetArgs setArgs);         // devuelve el valor anterior
void    setex(K key, long seconds, V value);             // TTL, pero no condicional
```

**Decisión**: `setAndChanged(key, value, new SetArgs().nx().ex(ttlSeconds))`. Es la única
firma que combina las tres cosas que el filtro necesita: condicionalidad NX, expiración y un
resultado que diga si la escritura ocurrió. Devuelve `true` si insertó, `false` si la clave
ya existía — que es exactamente "ya procesado, descartar".

**Por qué importa**: haber escrito `set(...)` de memoria habría compilado y desplegado un
filtro de idempotencia que no filtra nada. El fallo no sería visible en tiempo de
compilación; se habría manifestado como duplicados intermitentes en producción, absorbidos en
silencio por el `UNIQUE` de la base con un coste de rendimiento que nadie habría atribuido a
esta línea.

**Alternativas descartadas**: `setnx` + `key().expire()` son dos viajes no atómicos, con
ventana en la que una clave queda sin TTL si el proceso cae entre medias. `setGet` obligaría
a comparar el valor anterior contra `null`, más indirecto sin ninguna ventaja.

---

## R-09 — P-02 · Autenticación SASL del bróker de Confluent Cloud

**Fuente**: issue quarkusio/quarkus#15448 y documentación de Confluent Cloud.

**Decisión**: prefijo global `kafka.`, que Quarkus propaga a todos los canales.

```yaml
kafka:
  bootstrap.servers: ${KAFKA_BOOTSTRAP_SERVERS}
  security.protocol: SASL_SSL
  sasl.mechanism: PLAIN
  sasl.jaas.config: >-
    org.apache.kafka.common.security.plain.PlainLoginModule required
    username='${KAFKA_API_KEY}' password='${KAFKA_API_SECRET}';
```

**Detalles que no se pueden deducir**: el valor de `sasl.jaas.config` **debe terminar en
punto y coma**; sin él la línea no parsea. Las credenciales van entre comillas simples.

---

## R-10 — P-03 · Autenticación del Schema Registry de Confluent Cloud

**Fuente**: misma issue y documentación de Confluent Cloud.

**Decisión**:

```yaml
kafka:
  schema.registry.url: ${SCHEMA_REGISTRY_URL}
  basic.auth.credentials.source: USER_INFO
  basic.auth.user.info: ${SCHEMA_REGISTRY_API_KEY}:${SCHEMA_REGISTRY_API_SECRET}
```

**Tres hallazgos que evitan un 401**:

1. **Las credenciales del Schema Registry son distintas de las del clúster.** Confluent Cloud
   emite dos pares de API key/secret independientes. Reutilizar las del bróker da 401. Esto
   justifica que la constitución IV declare seis variables y no tres.
2. El formato de `basic.auth.user.info` es `KEY:SECRET`, separados por dos puntos.
3. Existen dos nombres para la misma propiedad: `basic.auth.user.info` cuando la consume
   directamente el serde Avro — nuestro caso — y `schema.registry.basic.auth.user.info`
   cuando un componente prefija la configuración del registry, como Kafka Connect. Usar el
   segundo aquí no tiene efecto.

**Riesgo descartado**: la issue #15448 reporta que esta autenticación falla, pero **solo en
modo nativo**; en JVM funciona. El plan empaqueta en JVM, así que no nos afecta. Habría sido
un bloqueante serio de haber elegido nativo.

---

## R-11 — P-04 · Configuración de `quarkus-openapi-generator-server`

**Fuente**: documentación de Quarkiverse.

**Decisión**:

La configuración final, con el generador ya decidido, está en **D-01** al final de este
documento. Lo que sigue son las propiedades y sus trampas, comunes a ambos generadores.

**Trampa de nomenclatura**: las extensiones *client* y *server* usan espacios de nombres
distintos y no intercambiables.

| Extensión | Prefijo |
| --- | --- |
| Server (la nuestra) | `quarkus.openapi.generator.server.*` — todo con puntos |
| Client | `quarkus.openapi-generator.codegen.spec.<fichero>.*` — con guion y `codegen` |

Copiar el prefijo del client, que es el que más aparece en ejemplos, deja la configuración
sin efecto sin avisar.

**`use-reactive: false`** es obligatorio para nosotros: la constitución IX exige modelo
bloqueante con virtual threads.

**Salida generada**: `target/generated-sources/jaxrs` con `apicurio` —el elegido—, o
`target/generated-sources/quarkus-openapi-generator-server` con `openapitools`. No se
versiona en ninguno de los dos casos.

---

## R-12 — P-05 · Generación de código Avro

**Fuente**: documentación de Apache Avro y `AbstractAvroMojo`, más comprobación empírica del
código generado durante la implementación.

> **Corregida durante la implementación.** La decisión original era usar el
> `avro-maven-plugin` directamente. Al activar `quarkus-confluent-registry-avro` resultó que
> arrastra `quarkus-avro`, cuyo propio codegen **también** compila `src/main/avro`: los dos
> generadores producían las mismas clases y el build fallaba con `duplicate class`.
>
> Peor aún, comprobando los dos árboles generados: el de Quarkus emitía
> `private java.nio.ByteBuffer amount;` y el del plugin
> `private java.math.BigDecimal amount;`. Es decir, el hallazgo de abajo aplica **igual** al
> generador de Quarkus, que tiene su propio `enableDecimalLogicalType`, también `false` por
> defecto.

**Decisión**: **un solo generador**, el de `quarkus-avro`. Se elimina el `avro-maven-plugin`
del `pom.xml` y se configura en `application.yaml`:

```yaml
avro:
  codegen:
    enableDecimalLogicalType: true
    stringType: String
```

Nótese el prefijo: son propiedades `avro.codegen.*`, **sin** `quarkus.` delante. No son
configuración de Quarkus aunque las lea su codegen.

**Verificado** tras el cambio: el campo generado es `private java.math.BigDecimal amount;`.

**Hallazgo decisivo**: **`enableDecimalLogicalType` vale `false` por defecto.** Con el
default, los campos `bytes` con `logicalType decimal` se generan como **`ByteBuffer`**, no
como `BigDecimal`. Toda la cadena de precisión que el plan construye —`NUMERIC(19,2)` en
base, `BigDecimal` en el dominio, `string` en REST— se rompería justo en la frontera Avro, y
el mapper tendría que decodificar bytes a mano.

`stringType=String` evita que los campos `string` lleguen como `CharSequence`.
`createSetters=false` encaja con el carácter inmutable de un contrato de integración.

**Caveat que no nos afecta**: AVRO-2548 documenta que `stringType=String` hace que se ignoren
los conversores de `logicalType` **para UUID**. No usamos el logicalType `uuid` en ningún
esquema: los identificadores viajan como `string` plano. La combinación
`stringType=String` + `enableDecimalLogicalType=true` sí funciona.

---

## R-13 — P-06 · Pool JDBC y pinning de virtual threads

**Fuente**: issue quarkusio/quarkus#50345, changelog de pgjdbc, JEP 491 y la guía de
datasources de Quarkus.

### Pinning del driver — regresión introducida y ya corregida aguas arriba

La historia tiene tres tiempos, y el tercero cambia el remedio:

1. pgjdbc **42.6.0** sustituyó prácticamente todos los `synchronized` por `ReentrantLock`
   (PR #2635). Desde entonces, el consejo genérico de "el driver de Postgres bloquea virtual
   threads" quedó obsoleto.
2. **42.7.8 reintrodujo el pinning** (PR #3703, en `ResourceLock.obtain()`). El síntoma es
   `VirtualThread$VThreadContinuation.onPinned` con `reason:MONITOR`. La issue de Quarkus
   #50345 se cerró como bug de terceros, con la recomendación provisional de bajar de versión.
3. **42.7.9 lo corrigió.** El PR **#3849**, de Sanne Grinovero (Quarkus/Hibernate), declara en
   su descripción que responde a la issue *"Virtual Thread pinned in PostgreSQL JDBC Driver
   42.7.8"* y elimina la sincronización de `QueryExecutorBase#standardConformingStrings` y
   `QueryExecutorBase#transaction`. Fusionado en master y publicado en **42.7.9**
   (2026-01-15), junto con #3850, de la misma línea de trabajo.

**Decisión**: exigir `org.postgresql:postgresql` **>= 42.7.9**, preferentemente la última
42.7.x publicada (**42.7.13** en el momento de escribir esto).

**Cómo aplicarlo, en este orden**:

1. Comprobar qué versión resuelve el BOM de Quarkus:
   `mvn dependency:tree -Dincludes=org.postgresql:postgresql`
2. Si es **>= 42.7.9**, no hacer nada. Sobrescribir un BOM sin necesidad es peor que dejarlo.
3. Si es **42.7.8 exactamente**, fijar la versión en `<properties>` a la última 42.7.x, con un
   comentario que enlace este apartado.

Se exige un **mínimo**, no una versión exacta clavada. Fijar una versión antigua para huir de
la regresión habría sido cambiar un riesgo por otro: perder correcciones posteriores, incluidas
las de seguridad.

**Por qué esto importa tanto en este proyecto**: JEP 491, que elimina el pinning por
`synchronized` de raíz, llegó en **Java 24**. El proyecto está en **Java 21**, donde los
monitores siguen atados al carrier thread. No hay red de seguridad del runtime, así que la
versión del driver es la única defensa del modelo de concurrencia que exige la constitución IX.

**Verificación en tiempo de ejecución**: arrancar con `-Djdk.tracePinnedThreads=full` durante
una prueba de carga ligera y comprobar que no aparece ninguna traza `onPinned` que atraviese
`org.postgresql`. Es la única forma de confirmarlo de verdad, porque ni el BOM ni el
`pom.xml` lo garantizan por sí solos.

### Dimensionado del pool

```yaml
quarkus:
  datasource:
    jdbc:
      min-size: 5
      max-size: 16
```

**Rationale**: la guía de Quarkus enmarca el pool como mecanismo de *throttling*, no de
throughput. Con virtual threads esto es más marcado: los hilos son baratos y casi ilimitados,
las conexiones no. **El techo real de concurrencia es `max-size`**, por muchos virtual
threads que consuman mensajes. 16 es el valor del ejemplo oficial y suficiente para una demo
cuya concurrencia de consumo ya está acotada por el número de particiones.

**Caveat**: existe una interacción conocida entre `max-lifetime` y `min-size` por la que el
pool puede caer a 0 estando inactivo. No configuramos `max-lifetime`.

---

## D-01 — RESUELTO · Generador OpenAPI y versión del contrato

**Decisión**: se **descarta la extensión `quarkus-openapi-generator-server`** y se usa el
**`openapi-generator-maven-plugin` de OpenAPITools** con `jaxrs-spec` y `library=quarkus`.
Contrato en **OpenAPI 3.0.3**.

> **Corrección.** La primera versión de este documento planteaba elegir entre los dos
> generadores de la extensión de Quarkiverse. Eso era un falso dilema: la propia tabla
> comparativa oficial los declara **Preview** y **Experimental / early development**. Ninguno
> es base estable para construir encima. El análisis comparativo se conserva más abajo porque
> sigue siendo válido *dentro* de esa extensión, pero la conclusión es no usarla.

**Fuentes**: tabla comparativa oficial de Quarkiverse, issues OpenAPITools#20722, #12876,
#9083 y #14943, e issue Apicurio/apicurio-data-models#168.

### Primero: la prohibición de la constitución no aplica

La constitución III prohíbe Apicurio, pero la prohibición está redactada para el **registry y
el serde de Kafka** ("PROHIBIDO Apicurio o cualquier otro registry o serde"). Lo que la
extensión llama `apicurio` es el **generador de código OpenAPI** de Apicurio: otro proyecto,
otra función, ninguna relación con el Schema Registry. **No hay violación.** Queda anotado
aquí para que nadie lo lea como incumplimiento en una revisión futura.

### Segundo: ambos generadores son inmaduros, y uno lo es menos

La tabla comparativa oficial declara los niveles de madurez sin ambigüedad:

| | Apicurio | OpenAPITools |
| --- | --- | --- |
| **Madurez declarada** | **Preview** | **Experimental / early development** |
| Es el default | Sí | No |
| Antigüedad en la extensión | Generador original del lado servidor | Añadido después |
| Personalización | Por contrato, con `x-codegen-*`: por operación, por esquema, anotaciones de parámetro, tipo de retorno, clase base, formato de fecha | Por propiedades, global o por spec |
| Ventaja exclusiva | Tipado real de `multipart/form-data` | Propagación de perfiles SmallRye |
| Salida | `target/generated-sources/jaxrs` | `target/generated-sources/quarkus-openapi-generator-server` |

Ninguna de las ventajas exclusivas nos afecta: no hay `multipart` ni perfiles SmallRye en
nuestro contrato. Lo que decide es la madurez y el hecho de ser el camino por defecto y más
transitado de la extensión.

### Tercero: el hallazgo que obliga a bajar a 3.0.3

**Ningún generador del ecosistema soporta OpenAPI 3.1 con garantías.**

- OpenAPITools **no anuncia 3.1 como versión soportada**: su propio repositorio se describe
  como generador para "an OpenAPI Spec (v2, v3)". El issue #12876 reporta que generar desde un
  documento 3.1 produce un error de versión no soportada. #9083 y #14943 siguen abiertos como
  peticiones.
- **OpenAPITools#20722 describe exactamente nuestro caso**: al pasar un contrato de 3.0 a 3.1,
  **deja de compartir los tipos referenciados por `$ref`**. Dos ubicaciones que referencian el
  mismo esquema generan dos clases distintas en vez de una. `MonetaryAmount` se referencia
  **cuatro veces** en `ReconciliationResponse` (`paymentAmount`, `coreAmount`, `difference` y
  el propio componente): bajo 3.1 podríamos acabar con cuatro DTOs sin relación entre sí, y
  cuatro métodos de mapeo en lugar de uno.
- Apicurio no está mejor: su issue de investigación de 3.1 (#168, abierto en 2020 contra el
  `3.1.0-rc0`) se cerró sin evidencia de implementación, y el editor OpenAPI construido sobre
  `apicurio-data-models` 2.2.x declara soportar **"2.0 y 3.0.x"**.

**El contrato no necesita 3.1.** Lo único de 3.1 que usaba era `$ref` con una propiedad
`description` hermana en `coreAmount` y `difference`. En 3.0 las propiedades hermanas de
`$ref` se ignoran en silencio —las descripciones se habrían perdido sin aviso— y el idioma
correcto es envolver la referencia en un `allOf` de un solo elemento. Ya está aplicado en
`contracts/reconciliation-api.yaml`.

### Cuarto: la opción estable está fuera de Quarkiverse

La extensión de Quarkiverse es un envoltorio alrededor de generadores que sí son maduros. Se
puede usar el de OpenAPITools **directamente**, con su plugin Maven, y saltarse el envoltorio
inmaduro. El generador `jaxrs-spec` lleva años en producción y tiene una `library` dedicada a
Quarkus.

**Verificado en la documentación oficial del generador `jaxrs-spec`**: la opción `library`
acepta `<default>`, **`quarkus`**, `thorntail`, `openliberty`, `helidon` y `kumuluzee`.

### Configuración final

```xml
<plugin>
  <groupId>org.openapitools</groupId>
  <artifactId>openapi-generator-maven-plugin</artifactId>
  <!-- Fijar la última 7.x estable. 7.24.0 existe y está publicada. -->
  <version>7.24.0</version>
  <executions>
    <execution>
      <goals><goal>generate</goal></goals>
      <phase>generate-sources</phase>
      <configuration>
        <inputSpec>${project.basedir}/src/main/resources/openapi/reconciliation-api.yaml</inputSpec>
        <generatorName>jaxrs-spec</generatorName>
        <library>quarkus</library>
        <apiPackage>com.bancatest.reconciliation.infrastructure.adapter.in.rest.generated.api</apiPackage>
        <modelPackage>com.bancatest.reconciliation.infrastructure.adapter.in.rest.generated.model</modelPackage>
        <generateApiTests>false</generateApiTests>
        <generateModelTests>false</generateModelTests>
        <generateApiDocumentation>false</generateApiDocumentation>
        <generateModelDocumentation>false</generateModelDocumentation>
        <configOptions>
          <interfaceOnly>true</interfaceOnly>
          <useJakartaEe>true</useJakartaEe>
          <useSwaggerAnnotations>false</useSwaggerAnnotations>
          <dateLibrary>java8</dateLibrary>
          <returnResponse>false</returnResponse>
        </configOptions>
      </configuration>
    </execution>
  </executions>
</plugin>
```

**Tres opciones cuyo valor por defecto viola el plan o la constitución.** Ninguna se puede
dejar sin declarar:

| Opción | Default | Por qué hay que cambiarlo |
| --- | --- | --- |
| `dateLibrary` | `legacy` | Genera **`java.util.Date`**, que la constitución X prohíbe expresamente. `java8` produce tipos de `java.time` |
| `useSwaggerAnnotations` | `true` | Genera `@Schema` y `@Operation` en el código, justo lo que el plan prohíbe en la sección de contract-first. A `false` el contrato sigue siendo la única fuente |
| `useJakartaEe` | `false` | Genera el namespace `javax.*`. Quarkus 3 es `jakarta.*`: sin esto no compila |

`interfaceOnly=true` genera solo las interfaces JAX-RS y los modelos, sin esqueleto de
implementación: `ReconciliationResource` implementa la interfaz, que es lo que el plan pide.

`returnResponse=false` mantiene el retorno tipado. El 404 con `ProblemDetail` se produce con
un `ExceptionMapper`, no devolviendo `Response` sin tipo desde la interfaz.

**Alternativas descartadas**:

- **La extensión de Quarkiverse**, en cualquiera de sus dos generadores: Preview y
  Experimental. Es lo que motivó esta corrección.
- **Mantener 3.1 y convertir a 3.0 en el build**: añade herramienta y paso de compilación para
  un contrato de un endpoint. El Principio I lo prohíbe.

**Efecto colateral favorable de la decisión de 3.0.3**: `openapi-generator` es precisamente el
proyecto cuyo soporte de 3.1 es deficiente. Al haber bajado el contrato a 3.0.3 quedamos en su
terreno mejor probado, no en el problemático.

**Coste de la corrección**: cero código. No hay nada implementado. Solo cambia la
configuración del build y desaparece una dependencia.
