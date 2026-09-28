package com.conciliacion.application;

import com.conciliacion.domain.model.CircuitoContable;
import com.conciliacion.domain.model.CuentaBancaria;
import com.conciliacion.domain.model.CuentaContable;

import java.util.List;

/** Datos para armar los desplegables del filtro, en una sola llamada. */
public record OpcionesFiltro(
        List<CuentaBancaria> cuentasBancarias,
        List<CuentaContable> cuentasContables,
        List<CircuitoContable> circuitos) {
}
