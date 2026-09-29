package com.conciliacion.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

/** Cuenta del lado contable (la del sistema de contabilidad). */
@Entity
@Table(name = "cuenta_contable")
public class CuentaContable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(nullable = false, length = 30)
    private String codigo;

    @NotNull
    @Column(nullable = false, length = 100)
    private String nombre;

    /**
     * Id que esta cuenta tiene en Xubio. Lo que hace idempotente la
     * sincronizacion: se busca por este campo antes de insertar.
     *
     * UNIQUE y nullable porque las cuentas creadas a mano quedan en null, y
     * varias null no colisionan (H2 y PostgreSQL tratan los null como distintos
     * entre si).
     */
    @Column(name = "xubio_id", length = 60, unique = true)
    private String xubioId;

    public CuentaContable() {}

    public CuentaContable(String codigo, String nombre) {
        this.codigo = codigo;
        this.nombre = nombre;
    }

    public CuentaContable(String codigo, String nombre, String xubioId) {
        this(codigo, nombre);
        this.xubioId = xubioId;
    }

    public void actualizarDesde(String codigo, String nombre) {
        this.codigo = codigo;
        this.nombre = nombre;
    }

    public Long getId() { return id; }
    public String getCodigo() { return codigo; }
    public String getNombre() { return nombre; }
    public String getXubioId() { return xubioId; }
    public void setXubioId(String xubioId) { this.xubioId = xubioId; }
}
