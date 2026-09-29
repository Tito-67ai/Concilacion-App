package com.conciliacion.application.catalogo;

import java.util.List;

/**
 * Los catalogos que alimentan dos de los tres desplegables del filtro, en una sola
 * respuesta de Xubio.
 *
 * ── POR QUE SOLO DOS, Y NO TRES ───────────────────────────────────────────────
 *
 * El filtro tiene tres desplegables: cuenta bancaria, cuenta contable y circuito.
 * De esos, Xubio expone dos. NO existe en su API un recurso de cuentas bancarias
 * de la empresa: se recorrio la spec entera y el unico endpoint con "banco" en el
 * nombre es `GET /banco`, que devuelve el catalogo de entidades bancarias (Nacion,
 * Galicia, Santander), no las cuentas de la empresa, y sin CBU ni numero de
 * cuenta.
 *
 * El CBU es justamente el campo contra el que se concilia, asi que aunque el
 * endpoint existiera, no alcanza: una cuenta bancaria sin CBU no se puede usar
 * para conciliar un extracto. Rellenar el desplegable con `/banco` seria mostrar
 * bancos como si fueran cuentas, y el usuario no tendria forma de notar la
 * diferencia.
 *
 * Por eso las cuentas bancarias quedan como dato local (ver `DataSeeder`) y este
 * record trae dos listas y no tres. Agregar una tercera el dia que exista el
 * recurso es cambiar este record y un metodo; no deberia cambiar la decision.
 *
 * ── POR QUE VAN JUNTOS Y NO EN DOS LLAMADAS SUELTAS ────────────────────────────
 *
 * Porque la pantalla los pide en una llamada (GET /filtros/opciones). Como el
 * token ya esta resuelto, podrian ir en paralelo; agruparlos deja el lugar donde
 * poner el fan-out sin cambiar el contrato de salida.
 */
public record Catalogos(
        List<ItemCatalogo> cuentasContables,
        List<ItemCatalogo> circuitos) {

    public Catalogos {
        // Una lista null en la respuesta no es "sin cuentas", es un dato roto. Se
        // convierte en vacia para que el sincronizador no reviente con un NPE
        // mil metros adentro, y para que el log quede mostrando que falto el
        // campo en vez de mostrar un vacio que parece real.
        cuentasContables = cuentasContables == null ? List.of() : List.copyOf(cuentasContables);
        circuitos = circuitos == null ? List.of() : List.copyOf(circuitos);
    }

    /** Sin nada en los dos catalogos no se puede elegir un filtro. */
    public boolean vacio() {
        return cuentasContables.isEmpty() && circuitos.isEmpty();
    }

    public int total() {
        return cuentasContables.size() + circuitos.size();
    }
}
