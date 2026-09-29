package com.conciliacion.infrastructure.config;

import com.conciliacion.domain.model.CircuitoContable;
import com.conciliacion.domain.model.CuentaBancaria;
import com.conciliacion.domain.model.CuentaContable;
import com.conciliacion.domain.model.MovimientoBancario;
import com.conciliacion.domain.model.MovimientoContable;
import com.conciliacion.domain.model.OrigenMovimiento;
import com.conciliacion.infrastructure.persistence.CircuitoContableRepository;
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

/**
 * Carga inicial para que la pantalla tenga algo con que trabajar. H2 es en memoria con
 * create-drop, asi que corre en cada arranque y todo se pierde al reiniciar.
 *
 * ── QUE SE SIEMBRA Y QUE NO, Y POR QUE ────────────────────────────────────────
 *
 * Se siembran CUENTAS y CIRCUITOS porque sin ellos los desplegables de filtro estan
 * vacios y no hay forma de elegir nada: la app queda inservible, no "vacia".
 *
 * Se siembran MOVIMIENTOS PENDIENTES porque unMatching necesita dos filas en la
 * pantalla para que se vea de que se trata. No van conciliados: la pantalla arranca
 * con trabajo por hacer, que es el estado real de una empresa un dia 1.
 *
 * NO se siembran CONCILIACIONES HECHAS. Antes si, tres de agosto, "para que la
 * pantalla de conciliados tenga historial". Se sacaron: una conciliacion inventada es
 * un par banco-contable que no existio nunca, y un historial falso hace que la
 * funcionalidad se vea probada cuando en realidad no se toco. La pestaña de
 * conciliados arranca vacia y se llena con lo que el usuario concilie de verdad.
 *
 * ── POR QUE ESTOS MOVIMIENTOS NO PASAN POR EL PUERTO DE IMPORTACION ──────────
 *
 * Antes si lo hacian, via un extractor "DEMO" que era un `List.of(...)` hardcodeado
 * en el propio codigo. Se boro ese extractor cuando los unicos que quedan son de
 * archivo (Excel y PDF) y de API, que todavia no existen.
 *
 * Que los movimientos de carga entren directo por repositorio es lo correcto: NO son
 * datos de una fuente, son filas de arranque. Fingir que vienen de un extractor
 * obliga a mantener un extractor falso que solo existe para esta clase.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final MovimientoBancarioRepository bancoRepo;
    private final MovimientoContableRepository contableRepo;
    private final CuentaBancariaRepository cuentaBancariaRepo;
    private final CuentaContableRepository cuentaContableRepo;
    private final CircuitoContableRepository circuitoRepo;

    public DataSeeder(MovimientoBancarioRepository bancoRepo,
                      MovimientoContableRepository contableRepo,
                      CuentaBancariaRepository cuentaBancariaRepo,
                      CuentaContableRepository cuentaContableRepo,
                      CircuitoContableRepository circuitoRepo) {
        this.bancoRepo = bancoRepo;
        this.contableRepo = contableRepo;
        this.cuentaBancariaRepo = cuentaBancariaRepo;
        this.cuentaContableRepo = cuentaContableRepo;
        this.circuitoRepo = circuitoRepo;
    }

    @Override
    public void run(String... args) {
        if (bancoRepo.count() > 0) {
            return;
        }

        // --- dimensional ------------------------------------------------------
        CuentaBancaria galicia = cuentaBancariaRepo.save(
                new CuentaBancaria("Cuenta Corriente", "Banco Galicia", "0000003100000003079083"));
        CuentaBancaria nacion = cuentaBancariaRepo.save(
                new CuentaBancaria("Caja de Ahorro", "Banco Nacion", "0110000430000043210987"));

        CuentaContable cuentasVentas = cuentaContableRepo.save(
                new CuentaContable("1.1.01.001", "Clientes - Ventas"));
        CuentaContable cuentasCompras = cuentaContableRepo.save(
                new CuentaContable("2.1.01.004", "Proveedores - Compras"));

        CircuitoContable ventas = circuitoRepo.save(new CircuitoContable("Ventas"));
        circuitoRepo.save(new CircuitoContable("Compras"));
        circuitoRepo.save(new CircuitoContable("Tesoreria"));

        // --- movimientos bancarios, todos PENDIENTES ---------------------------
        // El prefijo de comprobante es SEM (semilla), no DEMO: ya no existe ninguna
        // fuente "demo" en el sistema, asi que un comprobante que dice DEMO estaria
        // apuntando a algo que no esta. Estas filas tambien quedan sin origen de
        // archivo: se crean a mano.
        banco(galicia, "SEM-0001", LocalDate.of(2026, 9, 20), "VENTA MOSTRADOR FACT A",
                "152000.00", true);
        banco(galicia, "SEM-0002", LocalDate.of(2026, 9, 19), "PAGO PROVEEDOR ACME",
                "48000.00", false);
        banco(galicia, "SEM-0003", LocalDate.of(2026, 9, 18), "COMISION MANTENIMIENTO CUENTA",
                "1850.00", false);
        banco(galicia, "SEM-0004", LocalDate.of(2026, 9, 22), "TRANSFERENCIA RECIBIDA DE ACME",
                "45500.40", true);
        banco(galicia, "SEM-0005", LocalDate.of(2026, 8, 5), "COBRO FACTURA A-1042",
                "320000.00", true);
        banco(galicia, "SEM-0006", LocalDate.of(2026, 8, 12), "PAGO PROVEEDOR ACME",
                "96500.40", false);
        banco(nacion, "SEM-0007", LocalDate.of(2026, 8, 28), "COBRO FACTURA B-1107",
                "128750.00", true);

        // --- lado contable: UN pendiente, con par exacto ------------------------
        // Queda a proposito PENDIENTE y con los mismos fecha, importe y signo que
        // SEM-0001, para que el Autoconciliar tenga algo que resolver de verdad y la
        // fila se vea marcada como exacta en el panel derecho.
        contableRepo.save(new MovimientoContable("XUB-2026-091", LocalDate.of(2026, 9, 20),
                "VENTA MOSTRADOR FACT A", new BigDecimal("152000.00"), Boolean.TRUE,
                OrigenMovimiento.XUBIO_API, cuentasVentas, ventas));

        log.info("== CARGA INICIAL LISTA ==");
        log.info("  cuentas bancarias: 2 | contables: 2 | circuitos: 3");
        log.info("  movimientos bancarios: 7, todos PENDIENTES (ninguno conciliado)");
        log.info("  pendientes con par exacto: 1 (SEM-0001 <-> XUB-2026-091)");
        log.info("  conciliaciones: 0. La pestana de conciliados arranca vacia a proposito.");
    }

    private void banco(CuentaBancaria cuenta, String comprobante, LocalDate fecha,
                       String detalle, String importe, boolean esCredito) {
        bancoRepo.save(new MovimientoBancario(cuenta, fecha, fecha, detalle,
                new BigDecimal(importe), esCredito, comprobante, null, OrigenMovimiento.MANUAL));
    }
}
