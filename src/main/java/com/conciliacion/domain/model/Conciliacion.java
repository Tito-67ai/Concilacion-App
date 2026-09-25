package com.conciliacion.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Registro de una conciliacion ya hecha: junta el lado bancario con su par contable,
 * dejando guardado el resultado (conciliado o descartado) y de donde salio cada lado.
 */
@Entity
@Table(name = "conciliacion")
public class Conciliacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(nullable = false, length = 20)
    private EstadoConciliacion estado;

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(nullable = false, length = 20)
    private TipoMovimiento tipo;

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(nullable = false, length = 20)
    private OrigenMovimiento origen;

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

    @NotNull
    @Column(nullable = false)
    private LocalDateTime importadoEn;

    public Conciliacion() {}

    public Conciliacion(EstadoConciliacion estado, TipoMovimiento tipo, OrigenMovimiento origen,
                        LocalDate fecha, String detalle, BigDecimal importe,
                        Boolean esCredito, LocalDateTime importadoEn) {
        this.estado = estado;
        this.tipo = tipo;
        this.origen = origen;
        this.fecha = fecha;
        this.detalle = detalle;
        this.importe = importe;
        this.esCredito = esCredito;
        this.importadoEn = importadoEn;
    }

    public Long getId() { return id; }
    public EstadoConciliacion getEstado() { return estado; }
    public TipoMovimiento getTipo() { return tipo; }
    public OrigenMovimiento getOrigen() { return origen; }
    public LocalDate getFecha() { return fecha; }
    public String getDetalle() { return detalle; }
    public BigDecimal getImporte() { return importe; }
    public Boolean getEsCredito() { return esCredito; }
    public LocalDateTime getImportadoEn() { return importadoEn; }
}