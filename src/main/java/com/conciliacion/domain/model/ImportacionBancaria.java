package com.conciliacion.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Registro de una corrida de importacion bancaria: que se pidio, de donde, y cuanto
 * entrou. Sin esto, cuando una API trae 400 filas y 3 estan raras, no hay forma de
 * responder que paso.
 *
 * Es tambien la traza de por que una fila existe: cada movimiento-importado cuelga
 * de una de estas (via movimiento_bancario.importacion_id).
 *
 * `leidos - insertados - duplicados` da los descartados por error de formato, que es
 * lo unico que no queda en otra tabla.
 */
@Entity
@Table(name = "importacion_bancaria")
public class ImportacionBancaria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "cuenta_bancaria_id", nullable = false)
    private CuentaBancaria cuentaBancaria;

    /** De que clase de fuente vinieron: MANUAL, CSV, API_BANCARIA, XUBIO_API. */
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(nullable = false, length = 20)
    private OrigenMovimiento origen;

    /**
     * Que conector concreto produjo esto: DEMO, CSV, GALICIA_API, XUBIO_API.
     * Es texto y no un enum a proposito: cada banco nuevo agrega UN valor y no
     * obliga a tocar este enum ni el contrato del frontend.
     */
    @NotNull
    @Column(nullable = false, length = 40)
    private String extractor;

    private LocalDate desde;
    private LocalDate hasta;

    @Column(nullable = false)
    private int leidos;

    @Column(nullable = false)
    private int insertados;

    /** Filas que ya estaban: se redescargaron porque el rango se solapa. */
    @Column(nullable = false)
    private int duplicados;

    @NotNull
    @Column(nullable = false)
    private LocalDateTime iniciadaEn;

    private LocalDateTime terminadaEn;

    /** Texto libre para errores de autenticacion, rate limit, timeout, etc. */
    @Column(length = 1000)
    private String detalle;

    public ImportacionBancaria() {}

    public ImportacionBancaria(CuentaBancaria cuentaBancaria, OrigenMovimiento origen,
                              String extractor, LocalDate desde, LocalDate hasta) {
        this.cuentaBancaria = cuentaBancaria;
        this.origen = origen;
        this.extractor = extractor;
        this.desde = desde;
        this.hasta = hasta;
        this.iniciadaEn = LocalDateTime.now();
    }

    public void cerrar(int leidos, int insertados, int duplicados, String detalle) {
        this.leidos = leidos;
        this.insertados = insertados;
        this.duplicados = duplicados;
        this.detalle = detalle;
        this.terminadaEn = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public CuentaBancaria getCuentaBancaria() { return cuentaBancaria; }
    public OrigenMovimiento getOrigen() { return origen; }
    public String getExtractor() { return extractor; }
    public LocalDate getDesde() { return desde; }
    public LocalDate getHasta() { return hasta; }
    public int getLeidos() { return leidos; }
    public int getInsertados() { return insertados; }
    public int getDuplicados() { return duplicados; }
    public LocalDateTime getIniciadaEn() { return iniciadaEn; }
    public LocalDateTime getTerminadaEn() { return terminadaEn; }
    public String getDetalle() { return detalle; }
}
