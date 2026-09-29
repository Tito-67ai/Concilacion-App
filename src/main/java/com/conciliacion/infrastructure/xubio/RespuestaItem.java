package com.conciliacion.infrastructure.xubio;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Una fila de catalogo tal como la manda Xubio.
 *
 * ── EL MAPEO ES UN SUPUESTO, Y HAY QUE DECIRLO ────────────────────────────────
 *
 * El codigo que motiva esto define el DTO con `id` y `nombre`, y nada mas. Con eso
 * no se puede armar una `CuentaBancaria` nuestra, que ademas de nombre necesita
 * banco y CBU, ni una `CuentaContable`, que necesita codigo.
 *
 * Asi que este record acepta los tres nombres que se usaron como alias, y el
 * mapeo a nuestras entidades esta en `SincronizadorCatalogos`. Todo eso es un
 * SUPUESTO tomado del snippet, no de una respuesta real de Xubio. Cuando se
 * tenga el client-id y se pueda llamar a la API, esto se corrige contra lo que
 * conteste de verdad: si el CBU viene en `cbu` y no en `codigo`, el alias se
 * corre de lugar.
 *
 * Por eso el record no falla si un campo no esta: `null` es un valor legitimo
 * aqui, y el sincronizador tiene reglas documentadas para cada falta.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RespuestaItem {

    private String id;

    @JsonAlias({"name", "descripcion", "label"})
    private String nombre;

    /** Codigo de la cuenta contable; CBU de la bancaria. */
    @JsonAlias({"code", "codigoContable", "cbu"})
    private String codigo;

    /** Banco. */
    @JsonAlias({"bank", "bancoNombre", "entidad"})
    private String grupo;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }

    public String getGrupo() { return grupo; }
    public void setGrupo(String grupo) { this.grupo = grupo; }
}
