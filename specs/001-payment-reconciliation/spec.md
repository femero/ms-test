# Feature Specification: Conciliación de Pagos

**Feature Branch**: `001-payment-reconciliation`

**Created**: 2026-09-20

**Status**: Draft

**Input**: User description: "Servicio de conciliación de pagos. DEMO técnica. Verifica que
cada pago reportado tenga su movimiento del core, y reporta los casos que no cuadran."

## Clarifications

### Session 2026-09-20

- Q: Cuando llega un movimiento del core para una conciliación ya resuelta, ¿quién decide qué
  pasa: el caso de uso antes de tocar el agregado, o la máquina de estados lanzando la
  excepción? → A: El caso de uso comprueba el estado primero y descarta con advertencia. La
  excepción de transición inválida queda como red de seguridad que el flujo normal nunca
  dispara.
- Q: Ambos mensajes traen identificador de evento, pero ningún requisito lo consume. ¿Sobre
  qué dato se construye entonces la idempotencia? → A: Solo sobre la referencia de operación,
  en ambos niveles. El identificador de evento queda como dato informativo.
- Q: FR-022 manda los mensajes inválidos a un "canal de mensajes fallidos" que no existe en la
  tabla de canales, donde solo hay las dos DLQ de los consumers. ¿Son lo mismo? → A: Sí. El
  canal de mensajes fallidos es la DLQ del consumer que recibió el mensaje. No hay un séptimo
  canal.
- Q: Sin outbox, publicar "exactamente una vez" es inalcanzable: un fallo entre el commit y la
  publicación pierde el evento. ¿Qué cede, el criterio o la constitución? → A: Cede el
  criterio. La publicación pasa a ser "como máximo una vez" y la pérdida del evento se suma a
  las limitaciones conocidas.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Conciliación automática de un pago con su movimiento (Priority: P1)

El área de operaciones necesita saber, sin intervención manual, si cada pago que el sistema
de pagos reporta tiene su movimiento equivalente en el core bancario, y si los importes
coinciden. Cuando llega un pago reportado, queda registrado como pendiente. Cuando llega el
movimiento del core con la misma referencia de operación, el servicio compara los importes y
resuelve la conciliación: conforme si la diferencia está dentro de la tolerancia, discrepante
si no lo está. El resultado se publica en el canal que corresponde a cada caso, de modo que
los sistemas aguas abajo lo consuman.

**Why this priority**: es la razón de ser del servicio. Sin esta historia no hay producto:
las demás solo consultan o protegen el resultado que esta produce.

**Independent Test**: se envía un pago reportado y luego su movimiento del core, y se
verifica que la conciliación queda resuelta con el estado correcto y que el resultado aparece
publicado en el canal correspondiente. No requiere ninguna otra historia.

**Acceptance Scenarios**:

1. **Given** no existe conciliación para la referencia OP-1, **When** llega un pago reportado
   de PEN 100.00 con referencia OP-1, **Then** se crea una conciliación en estado PENDING y
   no se publica ningún resultado.
2. **Given** existe una conciliación PENDING para OP-1 con pago PEN 100.00, **When** llega el
   movimiento del core de PEN 100.00, **Then** la conciliación pasa a MATCHED con diferencia
   0.00 y el resultado se publica en el canal de conformes.
3. **Given** existe una conciliación PENDING para OP-1 con pago PEN 100.00, **When** llega el
   movimiento del core de PEN 99.50, **Then** la conciliación pasa a MATCHED con diferencia
   0.50.
4. **Given** existe una conciliación PENDING para OP-1 con pago PEN 100.00, **When** llega el
   movimiento del core de PEN 99.00, **Then** la conciliación pasa a MATCHED con diferencia
   1.00, por ser el límite exacto de la tolerancia.
5. **Given** existe una conciliación PENDING para OP-1 con pago PEN 100.00, **When** llega el
   movimiento del core de PEN 98.99, **Then** la conciliación pasa a MISMATCHED con
   diferencia 1.01.
6. **Given** existe una conciliación PENDING para OP-1 con pago PEN 100.00, **When** llega el
   movimiento del core de PEN 95.00, **Then** la conciliación pasa a MISMATCHED con
   diferencia 5.00 y el resultado se publica en el canal de discrepancias.
7. **Given** existe una conciliación PENDING para OP-1 con pago PEN 100.00, **When** llega el
   movimiento del core de PEN 100.50, **Then** la conciliación pasa a MISMATCHED con
   diferencia -0.50, porque el core cobró de más.

---

### User Story 2 - Descarte seguro de duplicados y entradas inválidas (Priority: P2)

El canal de entrada puede reenviar el mismo mensaje, y los sistemas origen pueden emitir
datos que no cumplen las reglas del negocio. El servicio debe absorber ambas situaciones sin
corromper el resultado ni detener el procesamiento: un pago repetido no duplica la
conciliación, un movimiento sobre una conciliación ya resuelta no la altera, un movimiento
sin conciliación asociada no crea una, y un mensaje inválido se aparta para revisión en lugar
de contaminar los datos.

**Why this priority**: la entrega repetida de mensajes es una certeza operativa, no una
excepción. Sin esta historia, un reenvío rutinario produce conciliaciones duplicadas o revive
un resultado ya cerrado, que es peor que no tener el servicio. Va por delante de la consulta
porque protege el dato que la consulta expone.

**Independent Test**: se reenvía el mismo pago reportado dos veces y se verifica que existe
una sola conciliación; se envía un movimiento sobre una conciliación ya resuelta y se
verifica que no cambia ni se publica nada.

**Acceptance Scenarios**:

1. **Given** ya existe una conciliación para OP-1, **When** llega otra vez un pago reportado
   con referencia OP-1, **Then** el pago se descarta sin efecto y sigue existiendo una sola
   conciliación, en su estado actual.
2. **Given** existe una conciliación MATCHED para OP-1, **When** llega un movimiento del core
   con referencia OP-1, **Then** el movimiento se descarta sin efecto, la conciliación
   conserva su estado y no se publica ningún resultado.
3. **Given** no existe conciliación para OP-9, **When** llega un movimiento del core con
   referencia OP-9, **Then** el movimiento se descarta, se registra una advertencia y no se
   publica ningún resultado.
4. **Given** el servicio está operativo, **When** llega un pago reportado de PEN 0.00,
   **Then** no se crea conciliación y el mensaje se deriva al canal de mensajes fallidos.
5. **Given** el servicio está operativo, **When** llega un pago reportado en USD, **Then** no
   se crea conciliación y el mensaje se deriva al canal de mensajes fallidos.

---

### User Story 3 - Consulta del detalle de una conciliación (Priority: P3)

Quien investiga una incidencia necesita ver el estado de una conciliación concreta y, si es
discrepante, la diferencia exacta, partiendo del único dato que maneja: la referencia de
operación.

**Why this priority**: es una capacidad de lectura sobre datos que las historias anteriores
ya producen. Aporta visibilidad, pero su ausencia no impide que el servicio concilie ni
publique.

**Independent Test**: sobre una conciliación existente, se consulta por su referencia de
operación y se verifica que devuelve su detalle; se consulta una referencia inexistente y se
verifica que responde no encontrada.

**Acceptance Scenarios**:

1. **Given** existe una conciliación MISMATCHED para OP-1 con diferencia 5.00, **When** se
   consulta por la referencia OP-1, **Then** se devuelve su detalle con el estado MISMATCHED y
   la diferencia 5.00.
2. **Given** no existe conciliación para OP-404, **When** se consulta por la referencia
   OP-404, **Then** se responde que no fue encontrada.

---

### Edge Cases

- **Diferencia en el límite exacto de la tolerancia**: una diferencia de exactamente PEN 1.00
  es MATCHED. El límite es inclusivo.
- **Diferencia negativa mínima**: cualquier diferencia negativa es MISMATCHED, sin importar su
  magnitud. Una diferencia de -0.01 no entra en tolerancia.
- **Movimiento que llega antes que su pago**: se descarta con advertencia (FR-021) y el pago,
  cuando llegue, queda PENDING de forma permanente. Reconocido como limitación (ver
  *Limitaciones conocidas*, punto 1).
- **Segundo movimiento sobre una conciliación aún PENDING**: el primero que llega la resuelve;
  el segundo encuentra una conciliación ya resuelta y se descarta por FR-020.
- **Pago y movimiento con montos válidos pero monedas distintas entre sí**: no puede darse,
  porque ambos se validan contra PEN en la entrada (FR-022) y cualquier otra moneda se aparta
  antes de llegar a la comparación.
- **Mensaje reentregado por el canal de entrada tras un fallo**: el reenvío se comporta como un
  duplicado (FR-019 para pagos, FR-020 para movimientos) y produce un único efecto.

## Requirements *(mandatory)*

### Functional Requirements

**Recepción y creación**

- **FR-001**: El sistema MUST recibir pagos reportados que contengan identificador de evento,
  referencia de operación, cuenta, monto, moneda y fecha.
- **FR-002**: El sistema MUST crear una conciliación en estado PENDING por cada pago reportado
  válido cuya referencia de operación no exista aún.
- **FR-003**: El sistema MUST recibir movimientos del core que contengan identificador de
  evento, referencia de operación, monto, moneda y fecha.
- **FR-004**: Al recibir un movimiento del core, el sistema MUST localizar la conciliación
  PENDING con esa referencia de operación y resolverla.

**Comparación y clasificación**

- **FR-005**: El sistema MUST calcular la diferencia como `monto del pago reportado − monto
  del movimiento del core`.
- **FR-006**: El sistema MUST clasificar la conciliación como MATCHED cuando la diferencia sea
  cero, o positiva y menor o igual a la tolerancia.
- **FR-007**: El sistema MUST clasificar la conciliación como MISMATCHED cuando la diferencia
  sea positiva y mayor a la tolerancia, o cuando sea negativa, cualquiera que sea su magnitud.
- **FR-008**: La tolerancia MUST ser un monto fijo con moneda, con valor PEN 1.00, obtenido de
  configuración y no embebido en la lógica.
- **FR-009**: El sistema MUST registrar la diferencia exacta en las conciliaciones MISMATCHED.

**Estados**

- **FR-010**: El sistema MUST reconocer exactamente tres estados: PENDING (esperando el
  movimiento del core), MATCHED (cuadra dentro de tolerancia) y MISMATCHED (no cuadra).
- **FR-011**: El sistema MUST permitir únicamente las transiciones `PENDING → MATCHED` y
  `PENDING → MISMATCHED`. MATCHED y MISMATCHED son terminales.
- **FR-012**: El sistema MUST rechazar cualquier transición no declarada en FR-011, de forma
  explícita y nunca en silencio. Este rechazo es una red de seguridad del modelo, no el
  mecanismo de descarte: el flujo normal comprueba el estado antes de intentar la transición
  (FR-020) y por tanto nunca llega a provocarlo.

**Publicación**

- **FR-013**: El sistema MUST publicar cada conciliación que pase a MATCHED en el canal de
  conformes, como máximo una vez.
- **FR-014**: El sistema MUST publicar cada conciliación que pase a MISMATCHED en el canal de
  discrepancias, como máximo una vez.
- **FR-014b**: La publicación MUST ocurrir después de confirmar la persistencia del nuevo
  estado. Un fallo posterior a esa confirmación puede perder el evento sin revertir la
  conciliación; esa pérdida es un riesgo aceptado (ver *Limitaciones conocidas*, punto 2) y MUST NOT
  provocar reintento ni compensación.
- **FR-015**: El sistema MUST NOT publicar nada cuando una conciliación permanece en PENDING,
  ni cuando un mensaje se descarta.

**Consulta**

- **FR-016**: Los usuarios MUST poder consultar el detalle de una conciliación por su
  referencia de operación, obteniendo al menos su estado y, si es MISMATCHED, la diferencia.
- **FR-017**: El sistema MUST responder "no encontrada" cuando se consulte una referencia de
  operación sin conciliación asociada.

**Unicidad y descartes**

- **FR-018**: El sistema MUST garantizar que exista como máximo una conciliación por
  referencia de operación. La referencia de operación MUST ser el único criterio de
  idempotencia, tanto en la garantía definitiva como en cualquier comprobación previa. El
  identificador de evento MUST NOT usarse para decidir si un mensaje ya fue procesado.
- **FR-019**: El sistema MUST descartar sin efecto todo pago reportado cuya referencia de
  operación ya tenga conciliación. El descarte MUST tratarse como procesamiento correcto, no
  como error.
- **FR-020**: El sistema MUST comprobar el estado de la conciliación antes de intentar
  resolverla. Si ya está resuelta, MUST descartar el movimiento del core sin efecto y
  registrarlo como advertencia, sin invocar transición alguna. El descarte MUST tratarse como
  procesamiento correcto, no como error, y por tanto el mensaje no se deriva al canal de
  mensajes fallidos.
- **FR-021**: El sistema MUST descartar todo movimiento del core sin conciliación asociada, y
  registrarlo como advertencia.
- **FR-022**: El sistema MUST considerar inválido todo pago o movimiento que cumpla cualquiera
  de estas condiciones, y derivarlo al canal de mensajes fallidos (DLQ) sin crear ni modificar
  ninguna conciliación:
  - monto cero o negativo;
  - moneda distinta de PEN;
  - referencia de operación ausente o en blanco;
  - número de cuenta ausente o en blanco, en un pago reportado.

  Las dos últimas condiciones son estructurales: sin referencia de operación no hay forma de
  vincular el mensaje con nada, y ambos campos viajan con valor por defecto vacío en el
  contrato de entrada, de modo que el caso es alcanzable. El canal de mensajes fallidos de cada mensaje MUST ser el
  canal de descarte del canal de entrada por el que llegó: un pago inválido va al de pagos
  reportados, un movimiento inválido al de movimientos del core. No existe un canal común
  adicional.

### Key Entities

- **Reconciliation (Conciliación)**: única entidad con identidad propia y ciclo de vida. Se
  identifica de forma unívoca por su referencia de operación. Agrupa el monto del pago
  reportado, el monto del movimiento del core una vez recibido, la diferencia calculada y el
  estado actual. Es el único punto de entrada para modificar cualquiera de esos datos.
- **ReportedPayment (Pago reportado)**: dato de entrada que origina una conciliación. Aporta
  identificador de evento, referencia de operación, cuenta, monto, moneda y fecha. No tiene
  ciclo de vida propio.
- **CoreTransaction (Movimiento del core)**: dato de entrada que resuelve una conciliación.
  Aporta identificador de evento, referencia de operación, monto, moneda y fecha. No se
  conserva como entidad independiente.
- **OperationReference (Referencia de operación)**: identificador de negocio que vincula un
  pago reportado con su movimiento del core. Es la clave única de la conciliación.
- **Amount (Monto)**: cantidad monetaria, siempre acompañada de su moneda. Nunca se maneja
  como número suelto.
- **AmountDifference (Diferencia)**: resultado de restar los dos montos. Es un atributo de la
  conciliación, no una entidad. Puede ser cero, positiva o negativa.
- **Tolerance (Tolerancia)**: monto fijo con moneda que delimita cuándo una diferencia
  positiva sigue considerándose conforme.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100% de los pagos reportados válidos produce exactamente una conciliación,
  sin intervención manual.
- **SC-002**: El 100% de las conciliaciones resueltas queda clasificada conforme a la regla de
  tolerancia. Los siete casos de importes recogidos en la Historia 1 se cumplen exactamente,
  incluidos el límite inclusivo de PEN 1.00 y el tratamiento de la diferencia negativa.
- **SC-003**: Reenviar el mismo pago reportado cualquier número de veces produce un único
  efecto observable: existe una sola conciliación y, mientras siga en PENDING, no se publica
  ningún resultado (FR-015).
- **SC-004**: Ninguna conciliación resuelta se publica más de una vez, ni en un canal que no
  corresponda a su estado, y ninguna conciliación PENDING genera publicación. En ausencia de
  fallos de infraestructura, el 100% de las conciliaciones resueltas queda publicado.
- **SC-005**: Ninguna entrada con monto cero o negativo, o en moneda distinta de PEN, llega a
  crear o modificar una conciliación; el 100% de ellas queda registrado en el canal de
  mensajes fallidos y disponible para revisión.
- **SC-006**: Una consulta por referencia de operación devuelve siempre una respuesta
  concluyente: el detalle de la conciliación o la indicación de que no existe. Nunca una
  respuesta ambigua ni vacía.
- **SC-007**: Ningún mensaje descartado por las reglas de duplicado o de movimiento huérfano
  detiene el procesamiento de los mensajes siguientes.

## Assumptions

- **Tolerancia**: PEN 1.00, tomada literalmente de la descripción. Se expone como
  configuración para poder ajustarla sin tocar la lógica.
- **Moneda única**: todo el alcance opera en PEN. Cualquier otra moneda es inválida por
  definición, no un caso a convertir.
- **Descarte como éxito**: un mensaje descartado por duplicado se considera procesado
  correctamente. Solo los mensajes inválidos por monto o moneda se derivan al canal de
  mensajes fallidos.
- **Advertencias**: "registrar como advertencia" significa dejar traza observable del descarte.
  No implica notificación a ninguna persona ni sistema.
- **Fechas**: el pago y el movimiento traen fecha, pero ninguna regla del alcance depende de
  ella. Se conserva como dato informativo; no se usa para agrupar, cortar ni caducar.
- **Cuenta**: el pago reportado trae cuenta, pero ninguna regla la utiliza. Se conserva como
  dato informativo del pago.
- **Identificador de evento**: ambos mensajes lo traen, pero ninguna regla lo utiliza. La
  idempotencia se ancla exclusivamente en la referencia de operación (FR-018). Se conserva
  como dato informativo y de trazabilidad.
- **Consulta sin control de acceso**: la autenticación y la autorización están fuera de
  alcance, por lo que la consulta se asume abierta dentro del entorno de la demo.

### Dependencias

- Los canales de entrada y salida, y el canal de mensajes fallidos, ya existen y los
  administra el equipo. El servicio solo los referencia por nombre.
- Los nombres de los seis canales **no viven en el repositorio**: se inyectan por variable de
  entorno, igual que los datos de conexión, y sin valor por defecto. Quien despliega los
  rellena con los nombres reales que el equipo haya creado en la plataforma.

  | Rol     | Canal                  | Variable                           |
  | ------- | ---------------------- | ---------------------------------- |
  | Consume | Pagos reportados       | `KAFKA_TOPIC_PAYMENTS_IN`          |
  | Consume | Pagos reportados — DLQ | `KAFKA_TOPIC_PAYMENTS_DLQ`         |
  | Consume | Movimientos del core   | `KAFKA_TOPIC_CORE_TRANSACTIONS_IN` |
  | Consume | Movimientos core — DLQ | `KAFKA_TOPIC_CORE_TRANSACTIONS_DLQ`|
  | Publica | Conformes              | `KAFKA_TOPIC_RESULTS_OUT`          |
  | Publica | Discrepancias          | `KAFKA_TOPIC_DISCREPANCIES_OUT`    |

  Si falta cualquiera, la aplicación no arranca. Es deliberado: un nombre de canal con valor
  por defecto haría que el servicio arrancase leyendo o escribiendo en el sitio equivocado, y
  el error se manifestaría como silencio en lugar de como fallo.

  Ningún requisito de este documento depende del nombre concreto de un canal.

- Los contratos de los mensajes de salida se entregan como archivos de esquema en el
  repositorio para que el equipo los registre. El servicio no los registra por sí mismo.

- **Los sistemas origen identifican cada mensaje con su referencia de operación al
  publicarlo.** De este supuesto depende que todos los mensajes de una misma operación se
  procesen en serie y en orden, y no en paralelo.

  Importa porque el servicio resuelve una conciliación en dos pasos —consultar su estado y
  luego transicionarla (FR-020)—. Si dos movimientos del core de la misma referencia se
  procesaran a la vez, ambos podrían leer el estado PENDING, ambos resolverla y ambos publicar,
  incumpliendo SC-004. Con los mensajes agrupados por referencia de operación, esa concurrencia
  no llega a producirse.

  **Es una dependencia, no una garantía propia**: si un sistema origen dejara de agrupar sus
  mensajes por referencia de operación, el problema reaparecería sin que este servicio pudiera
  detectarlo. El equipo debe confirmarlo con los productores.

### Limitaciones conocidas

Ambas son limitaciones aceptadas para esta demo, no defectos. Deben quedar documentadas en el
README del proyecto.

**1. Orden de entrega entre canales.** El orden entre canales distintos no está garantizado: un
movimiento del core puede llegar antes que su pago reportado. En ese caso el movimiento se
descarta por FR-021 y el pago, al llegar después, queda PENDING de forma permanente, sin
posibilidad de resolverse.

**2. Pérdida del evento de publicación.** La publicación del resultado ocurre después de
confirmar la persistencia, sin patrón outbox. Un fallo entre ambas deja la conciliación
correctamente resuelta y persistida, pero sin resultado publicado, y no existe mecanismo de
recuperación. Por eso FR-013 y FR-014 exigen "como máximo una vez" y no "exactamente una vez":
la duplicación sí se evita, la pérdida se asume.

## Fuera de Alcance

- Movimiento del core que llega antes que su pago reportado, y su posterior reconciliación.
- Detección de conciliaciones huérfanas o estancadas en PENDING.
- Fecha contable, hora de corte y archivo de cierre.
- Monedas distintas de PEN y conversión entre monedas.
- Listado, búsqueda o filtrado de conciliaciones. Solo consulta puntual por referencia de
  operación.
- Historial de transiciones, reintento manual y corrección automática.
- Notificaciones, interfaz gráfica, autenticación y autorización.
