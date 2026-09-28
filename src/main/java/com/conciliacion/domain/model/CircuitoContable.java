package com.conciliacion.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

/**
 * Circuito contable: el tipo de transaccion (ventas, compras, tesoreria, etc).
 * Es un atributo del LADO CONTABLE, no del bancario.
 */
@Entity
@Table(name = "circuito_contable")
public class CircuitoContable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(nullable = false, length = 60)
    private String nombre;

    public CircuitoContable() {}

    public CircuitoContable(String nombre) {
        this.nombre = nombre;
    }

    public Long getId() { return id; }
    public String getNombre() { return nombre; }
}
