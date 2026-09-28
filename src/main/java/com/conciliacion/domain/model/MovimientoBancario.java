package com.conciliacion.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Movimiento del lado del extracto bancario. Es "lo que dice el banco" que paso,
 * con su fecha, detalle e importe, y a que cuenta pertenece.
 *
 * El CBU ya no vive aca: esta en CuentaBancaria, y se lee con getCbu(). Dejarlo
 * duplicado como columna suelta era lo que impedia filtrar por cuenta.
 */
@Entity
@Table(name = "movimiento_bancario")
public class MovimientoBancario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(nullable = false)
    private LocalDate fecha;

    @NotNull
    @Column(nullable = false, length = 200)
    private String detalle;

    @NotNull
    @Column(nullable = false, precision = 13, scale = 2)
    private BigDecimal importe;

    @NotNull
    @Column(nullable = false)
    private Boolean esCredito;

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(nullable = false, length = 20)
    private EstadoConciliacion estado;

    /** Reemplaza al CBU suelto: ahora el movimiento pertenece a una cuenta. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "cuenta_bancaria_id", nullable = false)
    private CuentaBancaria cuentaBancaria;

    @NotNull
    @Column(nullable = false)
    private LocalDateTime importadoEn;

    public MovimientoBancario() {}

    public MovimientoBancario(CuentaBancaria cuentaBancaria, LocalDate fecha, String detalle,
                              BigDecimal importe, Boolean esCredito) {
        this.cuentaBancaria = cuentaBancaria;
        this.fecha = fecha;
        this.detalle = detalle;
        this.importe = importe;
        this.esCredito = esCredito;
        this.estado = EstadoConciliacion.PENDIENTE;
        this.importadoEn = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getCbu() { return cuentaBancaria.getCbu(); }
    public CuentaBancaria getCuentaBancaria() { return cuentaBancaria; }
    public LocalDate getFecha() { return fecha; }
    public String getDetalle() { return detalle; }
    public BigDecimal getImporte() { return importe; }
    public Boolean getEsCredito() { return esCredito; }
    public EstadoConciliacion getEstado() { return estado; }
    public LocalDateTime getImportadoEn() { return importadoEn; }
    public void setEstado(EstadoConciliacion estado) { this.estado = estado; }
}