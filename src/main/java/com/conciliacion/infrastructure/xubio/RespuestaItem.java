package com.conciliacion.infrastructure.xubio;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Una fila de catalogo tal como la manda Xubio.
 *
 * ── LOS NOMBRES DE CAMPO VIENEN DE LA SPEC, NO DE UN EJEMPLO ──────────────────
 *
 * Este record se escribio primero contra un fragmento de codigo generico que solo
 * definia `id` y `nombre`. Los nombres de campo de hoy salen de leer las
 * definiciones de la spec de Xubio:
 *
 *   CuentaContableBean     { ID, nombre, codigo, id }
 *   CircuitoContableBean   { circuitoContable_id, codigo, nombre }
 *
 * De ahi salen las dos rarezas que hacen que este record tenga alias:
 *
 *  1. `ID` y `id` conviven en el mismo bean. Es raro y parece un descuido de Xubio,
 *     pero esta escrito asi, asi que se aceptan los dos: si se aceptara solo uno y
 *     Xubio mandara el otro, todas las filas vendrian sin id, el sincronizador las
 *     descartaria y el catalogo apareceria vacio sin decir por que.
 *
 *  2. Los circuitos NO tienen `id`: el suyo se llama `circuitoContable_id`. Sin ese
 *     alias, ningun circuito seria sincronizable.
 *
 * ── POR QUE EL ID ES LO UNICO IMPORTANTE ──────────────────────────────────────
 *
 * Porque es lo que permite deduplicar contra lo que ya esta en la base. Un id
 * ausente o repetido no es un dato feo, es una fila que no se puede guardar sin
 * riesgo de duplicar el catalogo entero en cada arranque. Por eso el record no
 * falla si un campo no esta: `null` es un valor legitimo aca, y el sincronizador
 * tiene reglas documentadas para cada falta.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RespuestaItem {

    /**
     * `ID` en cuentas contables, `circuitoContable_id` en circuitos. Se aceptan los
     * tres nombres porque los tres aparecen en la API real.
     */
    @JsonAlias({"ID", "circuitoContable_id", "circuitoContableId"})
    private String id;

    @JsonAlias({"name", "descripcion", "label"})
    private String nombre;

    /** Codigo de la cuenta contable. Los circuitos tambien lo traen. */
    @JsonAlias({"code", "codigoContable"})
    private String codigo;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
}
