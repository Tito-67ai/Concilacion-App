package com.conciliacion.application.catalogo;

/**
 * De donde salen los catalogos del filtro.
 *
 * ── POR QUE ES UN PUERTO Y NO UNA LLAMADA DIRECTA A XUBIO ─────────────────────
 *
 * Por la misma razon que los extractores de archivo son un puerto. La pantalla
 * de conciliacion necesita listas de cosas, y de donde salen no es un dato que
 * deba saber: hoy es Xubio, pero mañana puede ser un ERP, o un archivo que baja
 * el usuario, o las dos cosas con un selector.
 *
 * Lo que NO se quiere es que esa duda se metan en el dominio: `ConciliacionService`
 * llamando a `restTemplate.getForObject(".../cuentas")` seria acoplar el matching
 * bancario a la API de un tercero, y el dia que se quiera probar sin red no habria
 * forma de hacerlo.
 *
 * Con este puerto, la prueba del matching es una clase de 15 lineas que devuelve
 * listas fijas, y la de Xubio es otra que no necesita red.
 */
public interface CatalogoXubio {

    /**
     * Consulta los catalogos que la fuente expone.
     *
     * <p>No son necesariamente los tres del filtro: son los que la fuente pueda
     * dar. De los tres desplegables, Xubio expone cuentas contables y circuitos,
     * pero no cuentas bancarias (ver `Catalogos`).
     *
     * @throws CatalogoNoDisponibleException si Xubio no esta configurado, no
     *                                       responde, o contesta con un error.
     *                                       NUNCA devuelve null ni una lista
     *                                       vacia por un fallo: un vacio y un
     *                                       error se ven igual en la pantalla y
     *                                       significan cosas distintas.
     */
    Catalogos consultar();

    /**
     * Si esta fuente esta prendida.
     *
     * Existe para que el arranque sepa si tiene que intentar la sincronizacion o
     * si conviene seguir con la base como esta. Sin esto, apagar Xubio y que
     * fallen las credenciales serian indistinguibles: las dos cosas arrancan con
     * un error.
     */
    boolean habilitado();

    /**
     * Como se llama esta fuente en la pantalla y en los logs. "Xubio" hoy.
     * Cuando haya una segunda fuente, el error dice cual fallo.
     */
    String nombre();
}
