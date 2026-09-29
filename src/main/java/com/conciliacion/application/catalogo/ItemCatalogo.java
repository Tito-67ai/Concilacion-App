package com.conciliacion.application.catalogo;

/**
 * Una fila de catalogo de Xubio, sin forma todavia.
 *
 * ── POR QUE UN SOLO RECORD PARA LOS TRES CATALOGOS ────────────────────────────
 *
 * Cuentas bancarias, cuentas contables y circuitos son tres cosas distintas, y
 * en el dominio cada una es su propia entidad. Aca, en la frontera con Xubio, se
 * comparten un record porque las tres vienen con la misma forma basica (id +
 * nombre) y porque la diferencia entre ellas esta en QUE campo se llena, no en
 * el tipo.
 *
 * Esa decision tiene un costo que hay que decir: si la API de Xubio manda
 * `codigo` para las contables pero no para los circuitos, y `grupo` para las
 * bancarias, los tres se llenan en un record comun y el mapeo queda en el
 * sincronizador, no en el tipo. Cuando aparezca un catalogo con una forma
 * realmente distinta, este record se parte en tres. Prefiero eso a tres records
 * identicos que solo se diferencian en nombres de campo.
 *
 * ── LOS CAMPOS QUE NO SON OBLIGATORIOS ────────────────────────────────────────
 *
 * `codigo` y `grupo` pueden venir null. Un circuito no tiene codigo, y una
 * cuenta bancaria puede no traer el banco. Lo que no puede faltar es `id`: sin
 * el, la fila no se puede deduplicar contra lo que ya esta en la base y cada
 * sincronizacion insertaria duplicados.
 */
public record ItemCatalogo(
        String id,
        String nombre,
        /** Codigo de la cuenta contable. En una cuenta bancaria, el CBU. */
        String codigo,
        /** Banco, en una cuenta bancaria. En los otros catalogos, no se usa. */
        String grupo) {

    /** El id es lo unico sin lo cual la fila no se puede sincronizar. */
    public boolean tieneId() {
        return id != null && !id.isBlank();
    }
}
