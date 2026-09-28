package com.conciliacion.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Registro de una conciliacion hecha: une el movimiento bancario con su par contable.
 *
 * Antes esto era una copia desnormalizada: guardaba fecha, importe y detalle, pero
 * NO guardaba a que movimientos se habia conciliado ni de que cuenta venian. Por eso
 * no se podia filtrar por cuenta, ni auditar, ni saber que se hizo con cada fila.
 *
 * Ahora guarda las dos cosas:
 *   - Los VINCULOS (movimientoBancario / movimientoContable): el "por que".
 *   - El SNAPSHOT de cuentas y circuito: el "que", para filtrar sin joins y para que
 *     un cambio posterior de cuentas no reescriba la historia.
 *
 * NOTA: se elimino el campo `tipo`. Un enum con BANCARIO/CONTABLE describe de que
 * lado viene un movimiento, pero esta fila ES la union de los dos lados, asi que el
 * campo no podia tener un valor con significado. Quedaba siempre en OTRO, que es
 * dato falso persistido.
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

    /** De donde salio el lado contable (XUBIO, MANUAL...). Antes hardcodeado en OTRO. */
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

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "movimiento_bancario_id", nullable = false)
    private MovimientoBancario movimientoBancario;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "movimiento_contable_id", nullable = false)
    private MovimientoContable movimientoContable;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "cuenta_bancaria_id", nullable = false)
    private CuentaBancaria cuentaBancaria;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "cuenta_contable_id", nullable = false)
    private CuentaContable cuentaContable;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "circuito_contable_id", nullable = false)
    private CircuitoContable circuito;

    @NotNull
    @Column(nullable = false)
    private LocalDateTime importadoEn;

    public Conciliacion() {}

    private Conciliacion(EstadoConciliacion estado, OrigenMovimiento origen, LocalDate fecha,
                         String detalle, BigDecimal importe, Boolean esCredito,
                         MovimientoBancario banco, MovimientoContable contable,
                         LocalDateTime importadoEn) {
        this.estado = estado;
        this.origen = origen;
        this.fecha = fecha;
        this.detalle = detalle;
        this.importe = importe;
        this.esCredito = esCredito;
        this.movimientoBancario = banco;
        this.movimientoContable = contable;
        this.cuentaBancaria = banco.getCuentaBancaria();
        this.cuentaContable = contable.getCuentaContable();
        this.circuito = contable.getCircuito();
        this.importadoEn = importadoEn;
    }

    /**
     * Factory method en vez de constructor con 13 parametros. Antes habia 8 posicionales
     * con dos enums indistinguibles en la llamada, y cambiar el origen por el tipo
     * compilaba sin error. Con un metodo con nombre no se puedeswapear nada.
     *
     * La fecha que se guarda es la del BANCO, que es la que el usuario ve en el
     * extracto. La contable puede diferir (dias de diferencia) y por eso el filtro
     * de fechas usa esta.
     */
    public static Conciliacion registrar(EstadoConciliacion estado,
                                         MovimientoBancario banco,
                                         MovimientoContable contable) {
        String detalle = textoNoVacio(contable.getConcepto(), banco.getDetalle(), "movimiento conciliado");
        return new Conciliacion(estado, contable.getOrigen(), banco.getFecha(), detalle,
                banco.getImporte(), banco.getEsCredito(), banco, contable, LocalDateTime.now());
    }

    private static String textoNoVacio(String primero, String segundo, String porDefecto) {
        if (primero != null && !primero.isBlank()) return primero;
        if (segundo != null && !segundo.isBlank()) return segundo;
        return porDefecto;
    }

    public Long getId() { return id; }
    public EstadoConciliacion getEstado() { return estado; }
    public OrigenMovimiento getOrigen() { return origen; }
    public LocalDate getFecha() { return fecha; }
    public String getDetalle() { return detalle; }
    public BigDecimal getImporte() { return importe; }
    public Boolean getEsCredito() { return esCredito; }
    public LocalDateTime getImportadoEn() { return importadoEn; }
    public MovimientoBancario getMovimientoBancario() { return movimientoBancario; }
    public MovimientoContable getMovimientoContable() { return movimientoContable; }
    public CuentaBancaria getCuentaBancaria() { return cuentaBancaria; }
    public CuentaContable getCuentaContable() { return cuentaContable; }
    public CircuitoContable getCircuito() { return circuito; }
}
