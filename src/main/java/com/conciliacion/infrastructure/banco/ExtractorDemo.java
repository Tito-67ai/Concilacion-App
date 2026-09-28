package com.conciliacion.infrastructure.banco;

import com.conciliacion.application.banco.ExtractorBancario;
import com.conciliacion.application.banco.MovimientoBancoCrudo;
import com.conciliacion.application.banco.RangoFechas;
import com.conciliacion.domain.model.CuentaBancaria;
import com.conciliacion.domain.model.OrigenMovimiento;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fuente de ejemplo. No habla con ningun banco: devuelve un set fijo de filas.
 *
 * Esta aqui por una razon concreta, no por adorno: hace que el camino de importacion
 * se ejercite en cada arranque. El DataSeeder entra por el MISMO puerto que un CSV o
 * que una API, asi que si el puerto anda, anduvo de verdad y no de palabra. Cuando
 * llegue el extractor de Galicia se agrega al lado y este se puede borrar.
 *
 * Los `comprobante` son fijos a proposito: si se corre la importacion dos veces, la
 * segunda tiene que dar 0 insertados y todo duplicados. Asi el UNIQUE se verifica en
 * vivo y no en theory.
 */
@Component
public class ExtractorDemo implements ExtractorBancario {

    /** Filas de ejemplo, con el CBU de la cuenta a la que pertenecen. */
    private record Demo(String cbu, String comprobante, LocalDate fecha, String detalle,
                        String importe, boolean esCredito) {}

    private static final List<Demo> FILAS = List.of(
            new Demo("0000003100000003079083", "DEMO-0001", LocalDate.of(2026, 9, 20),
                    "VENTA MOSTRADOR", "152000.00", true),
            new Demo("0000003100000003079083", "DEMO-0002", LocalDate.of(2026, 9, 19),
                    "PAGO PROVEEDOR", "48000.00", false),
            new Demo("0000003100000003079083", "DEMO-0003", LocalDate.of(2026, 9, 18),
                    "COMISION MANTENIMIENTO CUENTA", "1850.75", false),
            new Demo("0110000430000043210987", "DEMO-0004", LocalDate.of(2026, 9, 22),
                    "TRANSFERENCIA RECIBIDA", "75000.00", true),
            new Demo("0000003100000003079083", "DEMO-0005", LocalDate.of(2026, 8, 5),
                    "COBRO FACTURA A-1042", "320000.00", true),
            new Demo("0000003100000003079083", "DEMO-0006", LocalDate.of(2026, 8, 12),
                    "PAGO PROVEEDOR ACME", "96500.40", false),
            new Demo("0110000430000043210987", "DEMO-0007", LocalDate.of(2026, 8, 28),
                    "COBRO FACTURA B-1107", "128750.00", true));

    @Override
    public String codigo() { return "DEMO"; }

    @Override
    public String descripcion() { return "Datos de ejemplo (semilla de la demo)"; }

    @Override
    public OrigenMovimiento origen() { return OrigenMovimiento.MANUAL; }

    @Override
    public List<MovimientoBancoCrudo> extraer(CuentaBancaria cuenta, RangoFechas rango) {
        List<MovimientoBancoCrudo> salida = new ArrayList<>();
        for (Demo d : FILAS) {
            if (!d.cbu().equals(cuenta.getCbu())) continue;
            if (rango.desde() != null && d.fecha().isBefore(rango.desde())) continue;
            if (rango.hasta() != null && d.fecha().isAfter(rango.hasta())) continue;
            salida.add(new MovimientoBancoCrudo(d.fecha(), d.fecha(), d.detalle(),
                    new BigDecimal(d.importe()), d.esCredito(), d.comprobante(), null));
        }
        return salida;
    }
}
