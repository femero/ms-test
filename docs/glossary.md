# Glosario

Equivalencia entre el término de negocio y la clase en el código. El código fuente está
íntegramente en inglés; este documento es el puente.

| Término de negocio (es) | Clase / concepto (en) |
| ----------------------- | --------------------- |
| Conciliación            | `Reconciliation`      |
| Pago reportado          | `ReportedPayment` — llega como `PaymentExecuted` (contrato Avro) y se traduce a `ReconcilePaymentCommand` |
| Movimiento del core     | `CoreTransaction` — llega como `CoreTransactionReported` y se traduce a `RegisterCoreTransactionCommand` |
| Referencia de operación | `OperationReference`  |
| Monto                   | `Amount`              |
| Moneda                  | `Currency`            |
| Diferencia              | `AmountDifference`    |
| Tolerancia              | `Tolerance` — modelada como un `Amount`, servida por `ToleranceProvider` |

## Notas

**`Amount` y `AmountDifference` son tipos distintos, no una duplicación.** Un monto nunca es
negativo; una diferencia sí puede serlo, cuando el core cobró más de lo que el pago reportaba.
Con un solo tipo, ese caso —que el negocio considera siempre una discrepancia— sería
inexpresable.

**Los nombres de topic y las columnas de base de datos no se traducen**: conservan la
nomenclatura de negocio existente.

**"Movimiento del core" es `CoreTransaction`, no `BankTransaction`.** El espacio de nombres
del esquema Avro de entrada conserva `banking` porque identifica al sistema emisor, no al
concepto.
