package com.conciliacion.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

/**
 * Cuenta del lado del banco. Antes esto no existia: el CBU viajaba como texto suelto
 * en el movimiento, sin entidad que lo respaldara, y por eso no se podia filtrar nada.
 */
@Entity
@Table(name = "cuenta_bancaria")
public class CuentaBancaria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(nullable = false, length = 100)
    private String nombre;

    @NotNull
    @Column(nullable = false, length = 60)
    private String banco;

    @NotNull
    @Column(nullable = false, length = 30, unique = true)
    private String cbu;

    public CuentaBancaria() {}

    public CuentaBancaria(String nombre, String banco, String cbu) {
        this.nombre = nombre;
        this.banco = banco;
        this.cbu = cbu;
    }

    public Long getId() { return id; }
    public String getNombre() { return nombre; }
    public String getBanco() { return banco; }
    public String getCbu() { return cbu; }
}
