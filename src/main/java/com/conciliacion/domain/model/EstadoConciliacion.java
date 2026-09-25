package com.conciliacion.domain.model;

/** Estado del movimiento en el circuito: arranca PENDIENTE y el motor lo lleva a CONCILIADO (o DESCARTADO). */
public enum EstadoConciliacion {
    PENDIENTE,
    CONCILIADO,
    DESCARTADO
}