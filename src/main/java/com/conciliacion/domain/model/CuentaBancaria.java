package com.conciliacion.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

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

    /**
     * CBU de la cuenta.
     *
     * Antes era `nullable = false`. Con la sincronizacion contra Xubio dejo de
     * poder serlo: el catalogo de Xubio puede devolver la cuenta sin CBU, y con
     * la columna obligatoria un banco que no lo mande rompe el arranque entero en
     * vez de una fila.
     *
     * El UNIQUE se queda. Varias cuentas con CBU null NO colisionan, porque en
     * H2, PostgreSQL y MySQL los null se consideran distintos entre si, que es
     * justo lo que se quiere: dos cuentas sin CBU pueden convivir, pero dos
     * cuentas con el MISMO CBU no.
     */
    @Column(length = 30, unique = true)
    private String cbu;

    /**
     * Id que esta cuenta tiene en Xubio.
     *
     * Es lo que hace idempotente la sincronizacion: se busca por este campo antes
     * de insertar, asi que sincronizar dos veces no duplica las cuentas.
     *
     * UNIQUE y nullable por el mismo motivo que el CBU: las cuentas que crea el
     * usuario a mano, o las que estan en la base antes de que se conecte Xubio,
     * no tienen id de Xubio y todas pueden quedar en null sin pelearse.
     */
    @Column(name = "xubio_id", length = 60, unique = true)
    private String xubioId;

    /**
     * Cuando se conecto por ultima vez una fuente a esta cuenta. Es la marca de agua
     * que evita volver a bajar desde cero: las APIs bancarias piden una ventana de
     * fechas y siempre hay que pedir un margen hacia atras, porque un pago con valor
     * de hoy se puede confirmar mañana y aparecer en el rango siguiente.
     *
     * OJO: esto NO evita duplicados. Eso lo resuelve el UNIQUE de
     * (cuenta, comprobante) en movimiento_bancario; esto solo evita traffic
     * inutil. Para eso hace falta que la fuente entregue el comprobante.
     */
    private LocalDateTime ultimaImportacionEn;

    public CuentaBancaria() {}

    public CuentaBancaria(String nombre, String banco, String cbu) {
        this.nombre = nombre;
        this.banco = banco;
        this.cbu = cbu;
    }

    /** Para las que llegan de la sincronizacion. */
    public CuentaBancaria(String nombre, String banco, String cbu, String xubioId) {
        this(nombre, banco, cbu);
        this.xubioId = xubioId;
    }

    /** Refresca los datos que trae el catalogo, sin tocar el CBU si no vino. */
    public void actualizarDesde(String nombre, String banco) {
        this.nombre = nombre;
        this.banco = banco;
    }

    public Long getId() { return id; }
    public String getNombre() { return nombre; }
    public String getBanco() { return banco; }
    public String getCbu() { return cbu; }
    public String getXubioId() { return xubioId; }
    public void setXubioId(String xubioId) { this.xubioId = xubioId; }
    public LocalDateTime getUltimaImportacionEn() { return ultimaImportacionEn; }
    public void marcarImportadaAhora() { this.ultimaImportacionEn = LocalDateTime.now(); }
}
