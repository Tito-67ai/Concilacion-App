package com.conciliacion.application;

import com.conciliacion.domain.model.Conciliacion;

/**
 * Resultado de intentar autoconciliar, distinguiendo los dos casos que antes iban
 * los dos juntos en un `Optional.empty()`:
 *
 *   NO_EXISTE -> el id no existe (404)
 *   SIN_PAR   -> existe pero no hay coincidencia exacta (409)
 *
 * Confundirlos obligaba al cliente a mentir: "no tiene par exacto" sobre un id
 * inexistente es una afirmacion falsa que no ayuda a debuggear nada.
 */
public record ResultadoConciliacion(Estado estado, Conciliacion conciliacion) {

    public enum Estado { OK, NO_EXISTE, SIN_PAR }
}
