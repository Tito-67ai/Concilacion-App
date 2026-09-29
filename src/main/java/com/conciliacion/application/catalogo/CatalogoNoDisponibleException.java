package com.conciliacion.application.catalogo;

/**
 * No se pudieron leer los catalogos.
 *
 * ── POR QUE ES UNA EXCEPCION Y NO UNA LISTA VACIA ──────────────────────────────
 *
 * Porque en la pantalla las dos cosas se ven iguales: un desplegable sin
 * opciones. Con lista vacia el usuario cree que en Xubio no tiene cuentas
 * cargadas, que es un problema de su lado y se arregla en Xubio. Con esta
 * excepcion el backend responde 503 con un `detalle` que dice la verdad --
 * faltan credenciales, se veto el token, dio timeout -- y el usuario sabe que el
 * problema es de la app.
 *
 * El 503 y no el 500 a proposito: 500 dice "se rompio esto de aca", y aca no se
 * rompio nada, no se pudo leer un servicio de afuera. Los clientes HTTP entiende
 * que un 503 se puede reintentar; un 500 no.
 */
public class CatalogoNoDisponibleException extends RuntimeException {

    /**
     * Si reintentar tiene sentido. Un 401 no: las credenciales estan malas y
     * repetir la llamada devuelve lo mismo. Un timeout o un 503 de Xubio si, y el
     * front puede ofrecer "reintentar" solo en ese caso.
     */
    private final boolean reintentable;

    public CatalogoNoDisponibleException(String mensaje, boolean reintentable) {
        super(mensaje);
        this.reintentable = reintentable;
    }

    public CatalogoNoDisponibleException(String mensaje, boolean reintentable, Throwable causa) {
        super(mensaje, causa);
        this.reintentable = reintentable;
    }

    public boolean esReintentable() {
        return reintentable;
    }
}
