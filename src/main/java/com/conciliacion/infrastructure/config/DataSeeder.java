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
 * Se siembran MOVIMIENTOS PENDIENTES porque un Matching necesita dos filas en la
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
 * ── DE DONDE SACAN LAS CUENTAS, Y POR QUE NO ES IGUAL PARA TODAS ─────────────
 *
 * Las CUENTAS BANCARIAS se siembran SIEMPRE, con o sin Xubio prendido.
 *
 * Antes eran cosa de Xubio y esta clase se colgaba de lo que hubiera sin sembrar
 * nada. Hoy no: la API de Xubio no expone las cuentas bancarias de la empresa. Se
 * recorrio la spec entera y el unico recurso con "banco" en el nombre es
 * `GET /banco`, que devuelve el catalogo de entidades bancarias (Nacion, Galicia,
 * Santander), no las cuentas de la empresa, y sin CBU ni numero de cuenta.
 *
 * El CBU es el campo contra el que se concilia, asi que una cuenta bancaria sin CBU
 * no sirve para nada, y rellenar el desplegable con `/banco` seria mostrar bancos
 * como si fueran cuentas. Quedan como dato local, y por eso se siembran siempre:
 * son las unicas cuentas que quedan, sin el filtro de cuentas bancarias no hay
 * forma de importar un extracto, y las de abajo son cuentas de demostracion con
 * CBU de mentira, no datos de la empresa.
 *
 * Las CUENTAS CONTABLES y los CIRCUITOS vienen de Xubio si esta prendido, y en ese
 * caso NO se siembran. Meterle las de la semilla adelante seria mostrar cuatro
 * cuentas donde la empresa tiene las que tiene, dos de ellas inventadas, y el
 * usuario no tendria forma de saber cuales son reales: elegir la equivocada no da
 * ningun error, para el sistema es una cuenta de verdad.
 *
 * ── POR QUE CON XUBIO PRENDIDO TAMPOCO SE SIEMBRAN MOVIMIENTOS ───────────────
 *
 * Porque un movimiento de arranque colgado de una cuenta real de Xubio seria una
 * afirmacion falsa. El movimiento contable de la semilla viene con
 * `OrigenMovimiento.XUBIO_API`: dice "esto vino de la API", y si la API prendida y
 * funcional no lo produjo, es mentira. Y los bancarios, que dicen "SEM-0001", se
 * verian al lado de cuentas reales y parecerian datos de la empresa.
 *
 * Prendido y funcionando, la pantalla arranca con los desplegables llenos de
 * verdad y sin movimientos, que es el estado real de un sistema recien conectado.
 * El usuario importa su primer extracto y concilia.
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

        // --- Las bancarias: siempre nuestras, nunca de Xubio --------------------
        List<CuentaBancaria> bancos = cuentaBancariaRepo.findAllByOrderByNombreAsc();
        List<CuentaBancaria> bancosListo = completar(bancos, List.of(
                new CuentaBancaria("Cuenta Corriente", "Banco Galicia", "0000003100000003079083"),
                new CuentaBancaria("Caja de Ahorro", "Banco Nacion", "0110000430000043210987")),
                cuentaBancariaRepo::saveAndFlush);

        // ── Si hay una fuente de catalogos prendida, se corta aca ───────────────
        //
        // Los contables y los circuitos ya vienen de ahi, y los movimientos de
        // arranque no se siembran (por que esta escrito arriba). Con esto, Xubio
        // prendido y roto deja la pantalla con un 503 que dice por que, y Xubio
        // prendido y andando la deja con cuentas reales y sin datos inventados.
        if (sincronizador.sincronizado()) {
            log.info("== CARGA INICIAL PARCIAL ==");
            log.info("  cuentas bancarias: {} (locales, de demostracion: Xubio no expone "
                    + "las cuentas bancarias de la empresa, y hacen falta para importar).",
                    bancosListo.size());
            log.info("  cuentas contables: {} | circuitos: {}. Vienen de la fuente de "
                    + "catalogos, no se siembran.", cuentaContableRepo.count(), circuitoRepo.count());
            log.info("  movimientos: NO se siembran. Un movimiento de arranque colgado de una "
                    + "cuenta real seria una afirmacion falsa. Importa el primer extracto.");
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
        List<CuentaContable> contables = cuentaContableRepo.findAllByOrderByCodigoAsc();
        List<CircuitoContable> circuitos = circuitoRepo.findAllByOrderByNombreAsc();

        List<CuentaContable> contablesListo = completar(contables, List.of(
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
