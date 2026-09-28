package com.conciliacion.application.banco;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un movimiento tal como lo devuelve una fuente externa (CSV de Galicia, API de un
 * banco, API de Xubio), SIN tocar la base.
 *
 * Existe para que el parser de cada banco no tenga que conocer la entidad JPA, ni sus
 * validaciones, ni escribir en la base, ni decidir que hacer con los duplicados.
 *
 * Traduce: cada conector traduce SU formato a esto, y la ingesta se encarga una sola
 * vez de deduplicar, persistir y registrar la corrida.
 *
 * Todos los campos son opcionales salvo `fecha`, `detalle`, `importe` y `esCredito`:
 * no todos los bancos devuelven comprobante, ni saldo, ni fecha de operacion.
 */
public record MovimientoBancoCrudo(
        /** Fecha VALOR: la que figura en el extracto y la que usa el filtro de pantalla. */
        LocalDate fecha,
        /** Fecha de ejecucion. Puede diferir de la valor (cheques, retenciones). */
        LocalDate fechaOperacion,
        String detalle,
        BigDecimal importe,
        Boolean esCredito,
        /** ID de transaccion del banco. Sin esto no hay forma de evitar duplicados. */
        String comprobante,
        /** Saldo de la cuenta despues del movimiento, si la fuente lo da. */
        BigDecimal saldo) {
}
