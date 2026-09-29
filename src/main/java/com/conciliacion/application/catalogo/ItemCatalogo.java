package com.conciliacion.application.catalogo;

/**
 * Una fila de catalogo de Xubio, sin forma todavia.
 *
 * ── POR QUE UN SOLO RECORD PARA LOS DOS CATALOGOS ─────────────────────────────
 *
 * Cuentas contables y circuitos son dos cosas distintas, y en el dominio cada una
 * es su propia entidad. Aca, en la frontera con Xubio, se comparten un record
 * porque los dos beans de la spec tienen la misma forma (id, nombre y codigo) y
 * porque la diferencia entre ellos esta en QUE campo se llena, no en el tipo.
 *
 * Esa decision tiene un costo que hay que decir: si un catalogo devolviera una
 * forma realmente distinta, este record se parte en dos. Prefiero eso a dos
 * records identicos que solo se diferencian en nombres de campo.
 *
 * ── LOS CAMPOS QUE NO SON OBLIGATORIOS ────────────────────────────────────────
 *
 * `nombre` y `codigo` pueden venir null. Los beans de la spec los declaran, pero
 * no los marcan como obligatorios, y una cuenta contable sin `codigo` es posible
 * en la practica. Lo que no puede faltar es `id`: sin el, la fila no se puede
 * deduplicar contra lo que ya esta en la base y cada sincronizacion insertaria
 * duplicados.
 */
public record ItemCatalogo(
        String id,
        String nombre,
        /** Codigo de la cuenta contable, o del circuito si tambien lo trae. */
        String codigo) {

    /** El id es lo unico sin lo cual la fila no se puede sincronizar. */
    public boolean tieneId() {
        return id != null && !id.isBlank();
    }
}
