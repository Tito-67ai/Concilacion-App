package com.conciliacion.application;

import jakarta.validation.constraints.NotNull;

/**
 * Lo que elige la persona en la pantalla de matching: una fila del panel de banco
 * y una del panel contable.
 *
 * Un record con los dos ids en vez de tres parametros sueltos en la URL: el endpoint
 * es POST /api/conciliaciones y no POST /conciliaciones/{idBanco}?idContable=...
 * porque "conciliar estos dos" es una accion sobre un PAR, no sobre un movimiento.
 */
public record ParejaConciliacion(
        @NotNull Long idBanco,
        @NotNull Long idContable) {
}
