package com.conciliacion.infrastructure.config;

import com.conciliacion.application.catalogo.SincronizadorCatalogos;
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
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

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
 *
 * ── DE DONDE SACAN LAS CUENTAS ────────────────────────────────────────────────
 *
 * Antes esta clase se sembraba sola y creaba sus propias cuentas bancarias
 * (Galicia, Nacion) y contables. Con la sincronizacion contra Xubio eso daria un
 * problema visible: el desplegable de "Cuenta bancaria" mostraria cuatro cuentas,
 * dos de ellas de la empresa y dos inventadas aqui, y el usuario no tendria forma
 * de saber cuales son reales.
 *
 * Asi que si ya hay cuentas (porque la sincronizacion de Xubio corrio antes, que es
 * lo que hace el @Order(0) de SincronizadorCatalogosArranque), la semilla se
 * cuelga de esas y NO crea ninguna. Solo crea las suyas si la base esta realmente
 * vacia, que es el caso de una maquina sin Xubio prendido.
 */
@Component
@Order(100)
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final MovimientoBancarioRepository bancoRepo;
    private final MovimientoContableRepository contableRepo;
    private final CuentaBancariaRepository cuentaBancariaRepo;
    private final CuentaContableRepository cuentaContableRepo;
    private final CircuitoContableRepository circuitoRepo;
    private final SincronizadorCatalogos sincronizador;

    public DataSeeder(MovimientoBancarioRepository bancoRepo,
                      MovimientoContableRepository contableRepo,
                      CuentaBancariaRepository cuentaBancariaRepo,
                      CuentaContableRepository cuentaContableRepo,
                      CircuitoContableRepository circuitoRepo,
                      SincronizadorCatalogos sincronizador) {
        this.bancoRepo = bancoRepo;
        this.contableRepo = contableRepo;
        this.cuentaBancariaRepo = cuentaBancariaRepo;
        this.cuentaContableRepo = cuentaContableRepo;
        this.circuitoRepo = circuitoRepo;
        this.sincronizador = sincronizador;
    }

    @Override
    public void run(String... args) {
        if (bancoRepo.count() > 0) {
            return;
        }

        // ── Si hay una fuente de catalogos prendida, NO se siembra nada ──────────
        //
        // Es lo mas importante de esta clase. Con Xubio prendido, las cuentas van
        // a venir de ahi, y meterle las de la semilla adelante seria crear cuatro
        // cuentas donde la empresa tiene dos, dos de ellas inventadas. El
        // usuario no tendria forma de saber cuales son reales, y elegir la
        // equivocada no da ningun error: es una cuenta de verdad para el sistema.
        //
        // Y los movimientos tampoco se siembran: cuelgan de esas cuentas por clave
        // foranea, y sin cuentas no hay donde colgarlos. Un extractor de arranque
        // que no tiene donde colgar sus filas no tiene sentido.
        //
        // Con esto, Xubio prendido y roto deja la pantalla con los desplegables
        // vacios y un 503 que dice por que. Con Xubio apagado, la pantalla tiene
        // la semilla y funciona, que es el caso de una maquina de desarrollo.
        if (sincronizador.sincronizado()) {
            log.info("== CARGA INICIAL OMITIDA ==");
            log.info("  Hay una fuente de catalogos prendida, asi que las cuentas y los circuitos "
                    + "vienen de ahi y no se siembran. Los movimientos de arranque tambien se "
                    + "omiten porque cuelgan de esas cuentas.");
            return;
        }

        // --- dimensional: se usa lo que haya, se crea lo que falte ----------------
        // Cada catalogo se arma con su lista semilla COMPLETA, pero solo se
        // insertan las filas que no existen. Asi el caso normal (base vacia,
        // Xubio apagado) deja los desplegables con contenido para elegir, que es
        // lo que hace demostrable la pantalla, y el caso de una base a medio
        // camino no agrega nada que sobre.
        //
        // Para los movimientos alcanza la primera cuenta bancaria de cada tipo:
        // el segundo grupo de movimientos va a `cuentaBancaria2`, o a la misma si
        // solo hay una, porque un movimiento necesita una cuenta y no vale la
        // pena inventar una segunda para una sola fila.
        List<CuentaBancaria> bancos = cuentaBancariaRepo.findAllByOrderByNombreAsc();
        List<CuentaContable> contables = cuentaContableRepo.findAllByOrderByCodigoAsc();
        List<CircuitoContable> circuitos = circuitoRepo.findAllByOrderByNombreAsc();

        List<CuentaBancaria> bancosListo =completar(bancos, List.of(
                new CuentaBancaria("Cuenta Corriente", "Banco Galicia", "0000003100000003079083"),
                new CuentaBancaria("Caja de Ahorro", "Banco Nacion", "0110000430000043210987")),
                cuentaBancariaRepo::saveAndFlush);

        List<CuentaContable> contablesListo =completar(contables, List.of(
                new CuentaContable("1.1.01.001", "Clientes - Ventas"),
                new CuentaContable("2.1.01.004", "Proveedores - Compras")),
                cuentaContableRepo::saveAndFlush);

        List<CircuitoContable> circuitosListo = completar(circuitos, List.of(
                new CircuitoContable("Ventas"),
                new CircuitoContable("Compras"),
                new CircuitoContable("Tesoreria")),
                circuitoRepo::saveAndFlush);

        CuentaBancaria cuentaBancaria = bancosListo.get(0);
        CuentaBancaria cuentaBancaria2 = bancosListo.size() >= 2 ? bancosListo.get(1) : bancosListo.get(0);
        CuentaContable cuentaContable = contablesListo.get(0);
        CircuitoContable circuito = circuitosListo.get(0);

        // --- movimientos bancarios, todos PENDIENTES ---------------------------
        // El prefijo de comprobante es SEM (semilla), no DEMO: ya no existe ninguna
        // fuente "demo" en el sistema, asi que un comprobante que dice DEMO estaria
        // apuntando a algo que no esta. Estas filas tambien quedan sin origen de
        // archivo: se crean a mano.
        banco(cuentaBancaria, "SEM-0001", LocalDate.of(2026, 9, 20), "VENTA MOSTRADOR FACT A",
                "152000.00", true);
        banco(cuentaBancaria, "SEM-0002", LocalDate.of(2026, 9, 19), "PAGO PROVEEDOR ACME",
                "48000.00", false);
        banco(cuentaBancaria, "SEM-0003", LocalDate.of(2026, 9, 18), "COMISION MANTENIMIENTO CUENTA",
                "1850.00", false);
        banco(cuentaBancaria, "SEM-0004", LocalDate.of(2026, 9, 22), "TRANSFERENCIA RECIBIDA DE ACME",
                "45500.40", true);
        banco(cuentaBancaria, "SEM-0005", LocalDate.of(2026, 8, 5), "COBRO FACTURA A-1042",
                "320000.00", true);
        banco(cuentaBancaria, "SEM-0006", LocalDate.of(2026, 8, 12), "PAGO PROVEEDOR ACME",
                "96500.40", false);
        banco(cuentaBancaria2, "SEM-0007", LocalDate.of(2026, 8, 28), "COBRO FACTURA B-1107",
                "128750.00", true);

        // --- lado contable: UN pendiente, con par exacto ------------------------
        // Queda a proposito PENDIENTE y con los mismos fecha, importe y signo que
        // SEM-0001, para que el Autoconciliar tenga algo que resolver de verdad y la
        // fila se vea marcada como exacta en el panel derecho.
        contableRepo.save(new MovimientoContable("XUB-2026-091", LocalDate.of(2026, 9, 20),
                "VENTA MOSTRADOR FACT A", new BigDecimal("152000.00"), Boolean.TRUE,
                OrigenMovimiento.XUBIO_API, cuentaContable, circuito));

        log.info("== CARGA INICIAL LISTA ==");
        log.info("  cuentas bancarias: {} | contables: {} | circuitos: {}",
                cuentaBancariaRepo.count(), cuentaContableRepo.count(), circuitoRepo.count());
        log.info("  movimientos bancarios: 7, todos PENDIENTES (ninguno conciliado)");
        log.info("  pendientes con par exacto: 1 (SEM-0001 <-> XUB-2026-091)");
        log.info("  conciliaciones: 0. La pestana de conciliados arranca vacia a proposito.");
    }

    /**
     * Devuelve la lista que ya hay, mas las filas de la semilla que falten.
     *
     * <p>Se compara por nombre, no por id: las filas de la semilla son nuevas, con
     * id null hasta que se guardan, asi que no hay id con el cual compararlas. Y
     * el nombre es lo que el usuario ve en el desplegable, que es justo lo que
     * tiene que estar una sola vez.
     *
     * @param existentes lo que ya hay en la base
     * @param semilla    las filas de arranque, en el orden en que se desean
     * @param guardar    como se inserta una fila nueva
     */
    private static <T> List<T> completar(List<T> existentes, List<T> semilla, Function<T, T> guardar) {
        if (!existentes.isEmpty()) {
            return existentes;
        }
        return semilla.stream().map(guardar).toList();
    }

    private void banco(CuentaBancaria cuenta, String comprobante, LocalDate fecha,
                       String detalle, String importe, boolean esCredito) {
        bancoRepo.save(new MovimientoBancario(cuenta, fecha, fecha, detalle,
                new BigDecimal(importe), esCredito, comprobante, null, OrigenMovimiento.MANUAL));
    }
}
