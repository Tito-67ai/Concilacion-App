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

    public CuentaContable() {}

    public CuentaContable(String codigo, String nombre) {
        this.codigo = codigo;
        this.nombre = nombre;
    }

    public Long getId() { return id; }
    public String getCodigo() { return codigo; }
    public String getNombre() { return nombre; }
}
