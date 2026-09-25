package com.conciliacion.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Movimiento del lado contable (en el demo sale de Xubio). Es el papel del contador
 * con el que compara la conciliacion; el motor junta banco + contable por fecha e importe.
 */
@Entity
@Table(name = "movimiento_contable")
public class MovimientoContable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(nullable = false, length = 40)
    private String comprobante;

    @NotNull
    @Column(nullable = false)
    private LocalDate fecha;

    @NotNull
    @Column(nullable = false, length = 200)
    private String concepto;

    @NotNull
    @Column(nullable = false, precision = 13, scale = 2)
    private BigDecimal importe;

    @NotNull
    @Column(nullable = false)
    private Boolean esCredito;

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(nullable = false, length = 20)
    private OrigenMovimiento origen;

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(nullable = false, length = 20)
    private EstadoConciliacion estado;

    @NotNull
    @Column(nullable = false)
    private LocalDateTime importadoEn;

    public MovimientoContable() {}

    public MovimientoContable(String comprobante, LocalDate fecha, String concepto,
                              BigDecimal importe, Boolean esCredito, OrigenMovimiento origen) {
        this.comprobante = comprobante;
        this.fecha = fecha;
        this.concepto = concepto;
        this.importe = importe;
        this.esCredito = esCredito;
        this.origen = origen;
        this.estado = EstadoConciliacion.PENDIENTE;
        this.importadoEn = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getComprobante() { return comprobante; }
    public LocalDate getFecha() { return fecha; }
    public String getConcepto() { return concepto; }
    public BigDecimal getImporte() { return importe; }
    public Boolean getEsCredito() { return esCredito; }
    public OrigenMovimiento getOrigen() { return origen; }
    public EstadoConciliacion getEstado() { return estado; }
    public LocalDateTime getImportadoEn() { return importadoEn; }
    public void setEstado(EstadoConciliacion estado) { this.estado = estado; }
}