package com.conciliacion.application;

import com.conciliacion.domain.model.EstadoConciliacion;

import java.time.LocalDate;

/**
 * Filtro de la pantalla. Todos los campos son opcionales: null = "no filtrar por esto".
 *
 * `desde` y `hasta` se comparan contra la FECHA BANCARIA (la del extracto), no la
 * contable. Son distintas y por eso el criterio tiene que quedar escrito en la UI.
 */
public record FiltroConciliacion(
        Long cuentaBancariaId,
        Long cuentaContableId,
        Long circuitoId,
        LocalDate desde,
        LocalDate hasta,
        EstadoConciliacion estado) {

    public static FiltroConciliacion vacio() {
        return new FiltroConciliacion(null, null, null, null, null, null);
    }
}
