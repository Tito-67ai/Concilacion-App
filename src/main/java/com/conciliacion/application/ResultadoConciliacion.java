package com.conciliacion.application;

import com.conciliacion.domain.model.Conciliacion;

/**
 * Resultado de intentar conciliar, distinguiendo los casos que antes iban juntos:
 *
 *   NO_EXISTE           -> alguno de los dos ids no existe (404)
 *   SIN_PAR             -> existen pero no se pueden conciliar (409)
 *   SIGNO_INCOMPATIBLE  -> uno es credito y el otro debito (409, con detalle)
 *
 * Confundirlos obligaba al cliente a mentir: "no tiene par" sobre un id inexistente
 * es una afirmacion falsa que no ayuda a debuggear nada.
 *
 * SIGNO_INCOMPATIBLE esta separado de SIN_PAR a proposito. Es la falta mas comun al
 * conciliar a mano, y con el mensaje generico de SIN_PAR el usuario cree que el
 * problema es la fecha o el importe, cuando agarro un movimiento del lado
 * equivocado. Un 409 con detalle distinto deja que el frontend diga exactamente eso.
 */
public record ResultadoConciliacion(Estado estado, Conciliacion conciliacion, String detalle) {

    public ResultadoConciliacion(Estado estado, Conciliacion conciliacion) {
        this(estado, conciliacion, null);
    }

    public enum Estado { OK, NO_EXISTE, SIN_PAR, SIGNO_INCOMPATIBLE }

    public static ResultadoConciliacion ok(Conciliacion c) {
        return new ResultadoConciliacion(Estado.OK, c, null);
    }

    public static ResultadoConciliacion noExiste() {
        return new ResultadoConciliacion(Estado.NO_EXISTE, null, null);
    }

    public static ResultadoConciliacion sinPar() {
        return new ResultadoConciliacion(Estado.SIN_PAR, null, null);
    }

    public static ResultadoConciliacion signoIncompatible(String detalle) {
        return new ResultadoConciliacion(Estado.SIGNO_INCOMPATIBLE, null, detalle);
    }
}
