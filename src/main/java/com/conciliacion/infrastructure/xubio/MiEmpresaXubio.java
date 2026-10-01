package com.conciliacion.infrastructure.xubio;

/**
 * La respuesta de `GET /miempresa`: los datos de la empresa a la que pertenece el
 * par de credenciales que hizo el pedido.
 *
 * ── SOLO SE MAPEA `nombreEmpresa` ─────────────────────────────────────────────
 *
 * De los doce campos de `EmpresaBean` se trae uno. Los otros once (cuit, ingresos
 * brutos, direccion, localidad) son de la empresa, no de la conciliacion, y
 * copiarlos a la base seria guardar datos fiscales de un tercero que la app no
 * usa para nada.
 *
 * `EmpresaBean` en la spec viene con `nombreEmpresa`, `categoriaFiscal`,
 * `tipoDeCuenta`, `ingresosBrutos`, `fechaInicioActividad`, `direccion`, `pais`,
 * `provincia`, `localidad`, `telefono`, `email`, `facturam` y `cuit`. Si alguno se
 * llegara a necesitar, el nombre del campo va tal cual: Jackson mapea por nombre.
 *
 * NO es una entidad JPA: esto es solo la lectura del dato remoto. La empresa no se
 * persiste en la base, porque su identidad es el par de credenciales, que ya vive
 * en la configuracion, y duplicarla en dos lugares es una forma de que se
 * desincronicen sin que nadie lo note.
 */
class MiEmpresaXubio {

    private String nombreEmpresa;

    public String getNombreEmpresa() {
        return nombreEmpresa;
    }

    public void setNombreEmpresa(String nombreEmpresa) {
        this.nombreEmpresa = nombreEmpresa;
    }
}
