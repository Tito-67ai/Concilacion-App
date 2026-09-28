package com.conciliacion.domain.model;

/**
 * De donde salio el movimiento. Antes este enum solo se usaba del lado contable
 * (XUBIO / MANUAL / OTRO); ahora los dos lados lo usan.
 *
 * Importa que sea UN solo enum y no dos ("origen contable" y "origen bancario")
 * porque el problema es el mismo en los dos casos: cuando se reconecta una fuente,
 * hay que poder distinguir que filas son viejas y cuales recien bajadas.
 *
 * `XUBIO` paso a ser `XUBIO_API`: el nombre viejo no decia si venia de la API o de
 * una carga manual, y esa distincion es justamente la que hace falta para no
 * pisar con una sincronizacion lo que el usuario escribio a mano.
 */
public enum OrigenMovimiento {
    /** Alta desde la pantalla. */
    MANUAL,
    /** Archivo del banco subido por el usuario (CSV/XLSX). */
    CSV,
    /** API de un banco: Galicia, Santander, BBVA, etc. */
    API_BANCARIA,
    /** API de Xubio (lado contable). */
    XUBIO_API,
    OTRO
}
