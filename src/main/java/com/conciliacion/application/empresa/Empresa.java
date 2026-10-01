package com.conciliacion.application.empresa;

/**
 * Una empresa que la app puede operar.
 *
 * ── QUE ES UNA EMPRESA ACA ────────────────────────────────────────────────────
 *
 * Una App Cliente de Xubio. No una fila de una tabla de estudios, porque la API
 * publica de Xubio no tiene esa idea.
 *
 * Se comprobo leyendo la spec entera (https://xubio.com/API/1.1/swagger.json):
 * son 63 rutas, la palabra "estudio" no aparece ni una vez, y todas usan oauth2
 * `client_credentials` contra `/TokenEndpoint`. Cada par client-id / client-secret
 * pertenece a UNA sola empresa. El recurso mas cercano al concepto es
 * `GET /miempresa`, que devuelve `EmpresaBean` de esa empresa y no una lista.
 *
 * El login con usuario y password, que si muestra las empresas de un estudio,
 * pertenece a la app web de Xubio, no a su API. Reconstruir esa API private seria
 * guardar la contrasena del usuario y aceptar que puede dejar de andar sin aviso.
 *
 * ── POR QUE `clave` Y `nombre` SON DOS CAMPOS ──────────────────────────────────
 *
 * Son cosas distintas y no se derivan una de otra:
 *
 *   `clave`   la escribimos nosotros, en la configuracion. No puede cambiar sin
 *             romper la seleccion que el navegador guardo.
 *   `nombre`  lo devuelve `GET /miempresa`. Xubio lo puede cambiar en cualquier
 *             momento y este registro se entera solo.
 *
 * Si el nombre fuera un campo de configuracion, la lista mentiria en silencio: el
 * usuario leeria "Ferreteria Tito" mientras Xubio ya la llamo de otra forma, y no
 * habria forma de saber cual de las dos es la verdad.
 *
 * @param clave   identificador de configuracion, no un id de Xubio
 * @param nombre  como se llama la empresa en Xubio
 * @param estado  {@link #OK} o {@link #SIN_ACCESO}
 * @param detalle por que quedo SIN_ACCESO, o null
 */
public record Empresa(String clave, String nombre, String estado, String detalle) {

    public static final String OK = "OK";
    public static final String SIN_ACCESO = "SIN_ACCESO";

    /** Empresa sin acceso, con el motivo a la vista para que la UI lo muestre. */
    public static Empresa sinAcceso(String clave, String detalle) {
        return new Empresa(clave, clave, SIN_ACCESO, detalle);
    }
}
