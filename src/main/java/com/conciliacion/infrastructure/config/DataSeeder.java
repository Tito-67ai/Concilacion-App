package com.conciliacion.infrastructure.config;

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
import com.conciliacion.infrastructure.persistence.MovimientoBancarioRepository;
import com.conciliacion.infrastructure.persistence.MovimientoContableRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Siembra datos de ejemplo. H2 es en memoria con create-drop, asi que corre en cada
 * arranque y todo se pierde al reiniciar.
 *
 * Se cargan 2 cuentas bancarias, 2 contables y 3 circuitos para que los desplegables
 * del filtro tengan contenido, mas conciliaciones YA HECHAS de fechas anteriores:
 * sin esas, la pantalla de "movimientos conciliados" arranca vacia y no se puede
 * probar el filtro contra nada.
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

    public DataSeeder(MovimientoBancarioRepository bancoRepo,
                      MovimientoContableRepository contableRepo,
                      ConciliacionRepository conciliacionRepo,
                      CuentaBancariaRepository cuentaBancariaRepo,
                      CuentaContableRepository cuentaContableRepo,
                      CircuitoContableRepository circuitoRepo) {
        this.bancoRepo = bancoRepo;
        this.contableRepo = contableRepo;
        this.conciliacionRepo = conciliacionRepo;
        this.cuentaBancariaRepo = cuentaBancariaRepo;
        this.cuentaContableRepo = cuentaContableRepo;
        this.circuitoRepo = circuitoRepo;
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
        CircuitoContable tesoreria = circuitoRepo.save(new CircuitoContable("Tesoreria"));

        // --- pendientes de la demo (septiembre 2026) --------------------------
        // Este tiene par EXACTO, asi que el POST /autoconciliar lo resuelve solo.
        MovimientoBancario b1 = bancoRepo.save(new MovimientoBancario(galicia,
                LocalDate.of(2026, 9, 20), "VENTA MOSTRADOR",
                new BigDecimal("152000.00"), Boolean.TRUE));

        // Este no tiene par contable, asi que queda pendiente.
        MovimientoBancario b2 = bancoRepo.save(new MovimientoBancario(galicia,
                LocalDate.of(2026, 9, 19), "PAGO PROVEEDOR",
                new BigDecimal("48000.00"), Boolean.FALSE));

        // Comision del banco: nunca tendra contrapartida. Sirve para probar /descartar.
        MovimientoBancario b3 = bancoRepo.save(new MovimientoBancario(galicia,
                LocalDate.of(2026, 9, 18), "COMISION MANTENIMIENTO CUENTA",
                new BigDecimal("1850.75"), Boolean.FALSE));

        bancoRepo.save(new MovimientoBancario(nacion,
                LocalDate.of(2026, 9, 22), "TRANSFERENCIA RECIBIDA",
                new BigDecimal("75000.00"), Boolean.TRUE));

        // Par exacto de b1: se deja PENDIENTE a proposito, para que lo concilie el POST.
        contableRepo.save(new MovimientoContable("XUB-2026-091", LocalDate.of(2026, 9, 20),
                "VENTA MOSTRADOR FACT A", new BigDecimal("152000.00"), Boolean.TRUE,
                OrigenMovimiento.XUBIO, cuentasVentas, ventas));

        // --- conciliaciones YA HECHAS (agosto 2026) ---------------------------
        // Para que la pantalla de conciliados y el filtro tengan historial.
        conciliacionPrevia(galicia, cuentasVentas, ventas, "COBRO FACTURA A-1042",
                LocalDate.of(2026, 8, 5), "320000.00", true, "XUB-2026-072");
        conciliacionPrevia(galicia, cuentasCompras, compras, "PAGO PROVEEDOR ACME",
                LocalDate.of(2026, 8, 12), "96500.40", false, "XUB-2026-078");
        conciliacionPrevia(nacion, cuentasVentas, ventas, "COBRO FACTURA B-1107",
                LocalDate.of(2026, 8, 28), "128750.00", true, "XUB-2026-084");

        log.info("== SEED DEMO LISTO ==");
        log.info("  pendientes: 4 (uno con par exacto, uno sin par, una comision, una transferencia)");
        log.info("  conciliados de agosto: 3, para probar el filtro por cuenta, circuito y fechas");
        log.info("  cuentas bancarias: 2 | contables: 2 | circuitos: 3");
    }

    /** Crea un movimiento en ambos lados ya conciliados + su registro de conciliacion. */
    private void conciliacionPrevia(CuentaBancaria cuentaBancaria,
                                    CuentaContable cuentaContable,
                                    CircuitoContable circuito,
                                    String concepto, LocalDate fecha, String importe,
                                    boolean esCredito, String comprobante) {
        BigDecimal monto = new BigDecimal(importe);
        MovimientoBancario banco = bancoRepo.save(new MovimientoBancario(cuentaBancaria, fecha,
                concepto, monto, esCredito));
        MovimientoContable contable = contableRepo.save(new MovimientoContable(comprobante, fecha,
                concepto, monto, esCredito, OrigenMovimiento.XUBIO, cuentaContable, circuito));
        banco.setEstado(EstadoConciliacion.CONCILIADO);
        contable.setEstado(EstadoConciliacion.CONCILIADO);
        bancoRepo.save(banco);
        contableRepo.save(contable);
        conciliacionRepo.save(Conciliacion.registrar(EstadoConciliacion.CONCILIADO, banco, contable));
    }
}
