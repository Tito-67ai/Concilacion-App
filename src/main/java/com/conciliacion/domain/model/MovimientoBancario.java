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
 *
 * ─── Campos que se agregaron para poder conectar APIs bancarias ────────────────
 *
 * `comprobante`    Es el identificador de la transaccion segun el banco. Es la
 *                  pieza que hace posible sincronizar mas de una vez: los rangos
 *                  de fechas que piden las APIs se solapan entre llamadas, asi que
 *                  sin esto cada reconexion duplica las filas ya bajadas. Por eso
 *                  va con un UNIQUE compuesto con la cuenta: el mismo
 *                  comprobante en dos cuentas distintas es perfectamente valido,
 *                  en la misma cuenta es el mismo movimiento.
 *
 * `fechaOperacion` Cuando se ejecuto. `fecha` es la fecha VALOR, la que figura en
 *                  el extracto y la que usa el filtro de la pantalla. Un pago
 *                  acreditado el 28 con valor 30 es el caso tipico de descuadre:
 *                  se guarda la fecha valor (que es la que el usuario ve) y la de
 *                  operacion al lado para poder diagnosticar despues.
 *
 * `saldo`          Saldo de la cuenta despues del movimiento. Permite contrastar
 *                  el cierre del periodo contra el extracto. Opcional: ni todos los
 *                  bancos lo devuelven.
 *
 * `origen`         De que fuente salio la fila. Sin esto no se puede re-sincronizar
 *                  un banco sin pisar lo que otro trajo, ni descartar lo que el
 *                  usuario cargo a mano.
 */
@Entity
@Table(name = "movimiento_bancario",
        uniqueConstraints = @UniqueConstraint(name = "uk_mov_banco_cuenta_comprobante",
                columnNames = {"cuenta_bancaria_id", "comprobante"}))
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

    /**
     * Identificador de la transaccion segun el banco. NULL cuando la fila se cargo
     * a mano: en la mayoria de bases NULLs no colisionan en un UNIQUE, asi que las
     * altas manuales pueden repetirse sin estorbarse entre si.
     */
    @Column(length = 60)
    private String comprobante;

    @Column
    private LocalDate fechaOperacion;

    @Column(precision = 13, scale = 2)
    private BigDecimal saldo;

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(nullable = false, length = 20)
    private OrigenMovimiento origen;

    /**
     * Corrida de importacion de la que salio esta fila. NULL cuando se cargo a mano.
     * Sirve para el caso de support: "esta fila la bajo la API de Galicia ayer".
     *
     * Se asigna con setter y no por constructor porque la corrida se crea antes que
     * las filas (para que exista la FK) y recien ahi se conocen los conteos.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "importacion_bancaria_id")
    private ImportacionBancaria importacion;

    @NotNull
    @Column(nullable = false)
    private LocalDateTime importadoEn;

    public MovimientoBancario() {}

    /** Alta minima a mano. El origen queda MANUAL y sin comprobante ni saldo. */
    public MovimientoBancario(CuentaBancaria cuentaBancaria, LocalDate fecha, String detalle,
                              BigDecimal importe, Boolean esCredito) {
        this(cuentaBancaria, fecha, fecha, detalle, importe, esCredito,
                null, null, OrigenMovimiento.MANUAL);
    }

    /**
     * Constructor que usa la ingesta de una fuente (CSV, API). `fecha` es la fecha
     * valor del extracto; `fechaOperacion` la de ejecucion, y si la fuente no la
     * trae se usa la misma.
     */
    public MovimientoBancario(CuentaBancaria cuentaBancaria,
                              LocalDate fecha, LocalDate fechaOperacion, String detalle,
                              BigDecimal importe, Boolean esCredito,
                              String comprobante, BigDecimal saldo,
                              OrigenMovimiento origen) {
        this.cuentaBancaria = cuentaBancaria;
        this.fecha = fecha;
        this.fechaOperacion = fechaOperacion != null ? fechaOperacion : fecha;
        this.detalle = detalle;
        this.importe = importe;
        this.esCredito = esCredito;
        this.comprobante = comprobante;
        this.saldo = saldo;
        this.origen = origen;
        this.estado = EstadoConciliacion.PENDIENTE;
        this.importadoEn = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getCbu() { return cuentaBancaria.getCbu(); }
    public CuentaBancaria getCuentaBancaria() { return cuentaBancaria; }
    public LocalDate getFecha() { return fecha; }
    public LocalDate getFechaOperacion() { return fechaOperacion; }
    public String getDetalle() { return detalle; }
    public BigDecimal getImporte() { return importe; }
    public Boolean getEsCredito() { return esCredito; }
    public EstadoConciliacion getEstado() { return estado; }
    public LocalDateTime getImportadoEn() { return importadoEn; }
    public String getComprobante() { return comprobante; }
    public BigDecimal getSaldo() { return saldo; }
    public OrigenMovimiento getOrigen() { return origen; }
    public ImportacionBancaria getImportacion() { return importacion; }
    public void setImportacion(ImportacionBancaria importacion) { this.importacion = importacion; }
    public void setEstado(EstadoConciliacion estado) { this.estado = estado; }
}
