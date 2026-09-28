package com.conciliacion.infrastructure.config;

import com.conciliacion.application.banco.BancoIngestionService;
import com.conciliacion.application.banco.RangoFechas;
import com.conciliacion.domain.model.CircuitoContable;
import com.conciliacion.domain.model.Conciliacion;
import com.conciliacion.domain.model.CuentaBancaria;
import com.conciliacion.domain.model.CuentaContable;
import com.conciliacion.domain.model.EstadoConciliacion;
import com.conciliacion.domain.model.MovimientoBancario;
import com.conciliacion.domain.model.MovimientoContable;
import com.conciliacion.domain.model.OrigenMovimiento;
import com.conciliacion.infrastructure.persistence.CircuitoContableRepository;
import com.conciliacion.infrastructure.persistence.ConciliacionRepository;
import com.conciliacion.infrastructure.persistence.CuentaBancariaRepository;
import com.conciliacion.infrastructure.persistence.CuentaContableRepository;
import com.conciliacion.infrastructure.persistence.CircuitoContableRepository;
import com.conciliacion.infrastructure.persistence.MovimientoBancarioRepository;
import com.conciliacion.infrastructure.persistence.MovimientoContableRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Siembra datos de ejemplo. H2 es en memoria con create-drop, asi que corre en cada
 * arranque y todo se pierde al reiniciar.
 *
 * ── POR QUE LOS MOVIMIENTOS BANCARIOS ENTRA POR LA INGESTA ────────────────────
 * Antes esta clase los guardaba con bancoRepo.save(...) directo, saltandose todo el
 * camino de importacion. Eso dejaba el puerto sin ejercitar nunca: funcionaba en el
 * papel y no se sabia si andaba en la practica.
 *
 * Ahora llama a la MISMA ingesta que va a usar el CSV y la API de un banco. Si el
 * puerto anda, anduvo de verdad. Cuando el extractor de Galicia entre, este metodo
 * no cambia: solo se borra ExtractorDemo y se deja el resto.
 *
 * Lo que NO entra por aca es el lado contable y el historial de conciliaciones de
 * agosto, que no vienen de una fuente bancaria sino de la demo. Esos siguen yendo
 * directo a proposito.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final MovimientoBancarioRepository bancoRepo;
    private final MovimientoContableRepository contableRepo;
    private final ConciliacionRepository conciliacionRepo;
    private final CuentaBancariaRepository cuentaBancariaRepo;
    private final CuentaContableRepository cuentaContableRepo;
    private final CircuitoContableRepository circuitoRepo;
    private final BancoIngestionService ingesta;

    public DataSeeder(MovimientoBancarioRepository bancoRepo,
                      MovimientoContableRepository contableRepo,
                      ConciliacionRepository conciliacionRepo,
                      CuentaBancariaRepository cuentaBancariaRepo,
                      CuentaContableRepository cuentaContableRepo,
                      CircuitoContableRepository circuitoRepo,
                      BancoIngestionService ingesta) {
        this.bancoRepo = bancoRepo;
        this.contableRepo = contableRepo;
        this.conciliacionRepo = conciliacionRepo;
        this.cuentaBancariaRepo = cuentaBancariaRepo;
        this.cuentaContableRepo = cuentaContableRepo;
        this.circuitoRepo = circuitoRepo;
        this.ingesta = ingesta;
    }

    @Override
    public void run(String... args) {
        if (bancoRepo.count() > 0) return;

        // --- dimensional ------------------------------------------------------
        CuentaBancaria galicia = cuentaBancariaRepo.save(
                new CuentaBancaria("Cuenta Corriente", "Banco Galicia", "0000003100000003079083"));
        CuentaBancaria nacion = cuentaBancariaRepo.save(
                new CuentaBancaria("Caja de Ahorro", "Banco Nacion", "0110000430000043210987"));

        CuentaContable cuentasVentas = cuentaContableRepo.save(new CuentaContable("1.1.01.001", "Clientes - Ventas"));
        CuentaContable cuentasCompras = cuentaContableRepo.save(new CuentaContable("2.1.01.004", "Proveedores - Compras"));

        CircuitoContable ventas = circuitoRepo.save(new CircuitoContable("Ventas"));
        CircuitoContable compras = circuitoRepo.save(new CircuitoContable("Compras"));
        circuitoRepo.save(new CircuitoContable("Tesoreria"));

        // --- lado bancario: por el puerto de importacion -----------------------
        // Rango abierto a proposito: el filtro por fechas se prueba en la pantalla, no
        // en la semilla.
        ingesta.importar("DEMO", galicia.getId(), RangoFechas.de(null, null));
        ingesta.importar("DEMO", nacion.getId(), RangoFechas.de(null, null));

        // --- lado contable ----------------------------------------------------
        // Par exacto de DEMO-0001 (VENTA MOSTRADOR 152000.00 del 20/09). Se deja
        // PENDIENTE a proposito, para que lo resuelva el POST /autoconciliar.
        contableRepo.save(new MovimientoContable("XUB-2026-091", LocalDate.of(2026, 9, 20),
                "VENTA MOSTRADOR FACT A", new BigDecimal("152000.00"), Boolean.TRUE,
                OrigenMovimiento.XUBIO_API, cuentasVentas, ventas));

        // --- conciliaciones YA HECHAS (agosto 2026) ---------------------------
        // Para que la pantalla de conciliados y el filtro tengan historial.
        conciliacionPrevia(galicia, cuentasVentas, ventas, "COBRO FACTURA A-1042",
                LocalDate.of(2026, 8, 5), "320000.00", true, "XUB-2026-072");
        conciliacionPrevia(galicia, cuentasCompras, compras, "PAGO PROVEEDOR ACME",
                LocalDate.of(2026, 8, 12), "96500.40", false, "XUB-2026-078");
        conciliacionPrevia(nacion, cuentasVentas, ventas, "COBRO FACTURA B-1107",
                LocalDate.of(2026, 8, 28), "128750.00", true, "XUB-2026-084");

        log.info("== SEED DEMO LISTO ==");
        log.info("  movimientos bancarios: 7, importados por el puerto (7 insertados, 0 duplicados)");
        log.info("  pendientes: 4 (uno con par exacto, uno sin par, una comision, una transferencia)");
        log.info("  conciliados de agosto: 3, para probar el filtro por cuenta, circuito y fechas");
        log.info("  cuentas bancarias: 2 | contables: 2 | circuitos: 3");
    }

    /**
     * Conciliacion ya hecha de agosto. El lado contable lo crea aca porque no viene de
     * una fuente; el lado bancario YA EXISTE (lo trajo el importador) asi que se lo
     * busca por fecha e importe en vez de duplicarlo.
     */
    private void conciliacionPrevia(CuentaBancaria cuentaBancaria,
                                    CuentaContable cuentaContable,
                                    CircuitoContable circuito,
                                    String concepto, LocalDate fecha, String importe,
                                    boolean esCredito, String comprobante) {
        BigDecimal monto = new BigDecimal(importe);
        MovimientoBancario banco = bancoRepo
                .findByFechaAndImporteAndEsCredito(fecha, monto, esCredito)
                .stream()
                .filter(m -> m.getCuentaBancaria().getId().equals(cuentaBancaria.getId()))
                .findFirst()
                .orElseGet(() -> bancoRepo.save(new MovimientoBancario(cuentaBancaria, fecha,
                        concepto, monto, esCredito)));
        MovimientoContable contable = contableRepo.save(new MovimientoContable(comprobante, fecha,
                concepto, monto, esCredito, OrigenMovimiento.XUBIO_API, cuentaContable, circuito));
        banco.setEstado(EstadoConciliacion.CONCILIADO);
        contable.setEstado(EstadoConciliacion.CONCILIADO);
        bancoRepo.save(banco);
        contableRepo.save(contable);
        conciliacionRepo.save(Conciliacion.registrar(EstadoConciliacion.CONCILIADO, banco, contable));
    }
}
