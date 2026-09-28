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

    @NotNull
    @Column(nullable = false, length = 30, unique = true)
    private String cbu;

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

    public Long getId() { return id; }
    public String getNombre() { return nombre; }
    public String getBanco() { return banco; }
    public String getCbu() { return cbu; }
    public LocalDateTime getUltimaImportacionEn() { return ultimaImportacionEn; }
    public void marcarImportadaAhora() { this.ultimaImportacionEn = LocalDateTime.now(); }
}
