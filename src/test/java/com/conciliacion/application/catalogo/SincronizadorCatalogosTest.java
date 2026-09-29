package com.conciliacion.application.catalogo;

import com.conciliacion.domain.model.CuentaBancaria;
import com.conciliacion.infrastructure.persistence.CircuitoContableRepository;
import com.conciliacion.infrastructure.persistence.CuentaBancariaRepository;
import com.conciliacion.infrastructure.persistence.CuentaContableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Que la sincronizacion de catalogos haga lo que dice, y sobre todo lo que NO
 * dice, que es la parte peligrosa.
 *
 * ── POR QUE @DataJpaTest Y NO @SpringBootTest ──────────────────────────────────
 *
 * Porque lo que se prueba aca es la sincronizacion contra la base, y para eso
 * alcanza con la base y los repositorios. Con `@SpringBootTest` entrarian tambien
 * el `DataSeeder` y los runners de arranque, que insertan sus propias filas, y
 * ningun assert podria afirmar sobre lo que el test acaba de sembrar.
 *
 * El `@Import` mete a mano el `SincronizadorCatalogos` y un `@CatalogoFalso` que
 * devuelve lo que cada test quiere. Sin red, sin credenciales y sin Xubio.
 *
 * ── QUE CASOS CUBRE Y POR QUE CADA UNO ─────────────────────────────────────────
 *
 *  1. Inserta lo que no estaba. El camino feliz.
 *  2. Sincronizar dos veces NO duplica. Si esto falla, cada arranque de la app
 *     duplica el catalogo entero, y es el error mas caro de los posibles: la
 *     pantalla muestra la misma cuenta cuatro veces y el usuario no sabe cual
 *     elegir.
 *  3. Una cuenta que desaparece del catalogo NO se borra. Es la decision de
 *     diseño mas importante del sincronizador y por lo tanto la que mas merece
 *     un test: si se borrara, los movimientos historicos quedarian apuntando a
 *     una clave foranea que ya no existe, o habria que cascading y perder el
 *     historico. Las dos opciones son peores que dejar la fila.
 *  4. Filas sin id se descartan y se cuentan. Sin id no hay forma de
 *     deduplicar, asi que insertarlas seria crear duplicados en cada sync.
 *  5. Faltan nombre o codigo: se completa con algo estable, no con null. Las
 *     columnas son NOT NULL, asi que un null revienta el arranque entero en vez
 *     de una fila.
 *  6. La fuente apagada no hace nada.
 *  7. Un catalogo vacio de verdad NO borra lo que hay. Xubio contestando con
 *     listas vacias es un caso real (empresa sin cuentas cargadas) y tambien lo
 *     es el caso de que un despliegue haya roto el filtrado. En los dos, vaciar
 *     la pantalla seria lo peor que se puede hacer.
 */
@DataJpaTest
@Import({SincronizadorCatalogosTest.ConfigCatalogoFalso.class, SincronizadorCatalogos.class})
class SincronizadorCatalogosTest {

    @Autowired
    private SincronizadorCatalogos sincronizador;

    @Autowired
    private CatalogoFalso fuente;

    @Autowired
    private CuentaBancariaRepository bancoRepo;

    @Autowired
    private CuentaContableRepository contableRepo;

    @Autowired
    private CircuitoContableRepository circuitoRepo;

    @Autowired
    private TestEntityManager em;

    @BeforeEach
    void limpiar() {
        em.flush();
        em.clear();
        fuente.apagar();
    }

    @Test
    @DisplayName("Inserta las cuentas y los circuitos que no estaban")
    void insertaLoNuevo() {
        fuente.reemplazar(new Catalogos(
                List.of(new ItemCatalogo("x-1", "Cuenta Corriente 123", "0000003100000003079083", "Galicia")),
                List.of(new ItemCatalogo("c-1", "Clientes", "1.1.01.001", null)),
                List.of(new ItemCatalogo("k-1", "Ventas", null, null))));

        SincronizadorCatalogos.Resultado r = sincronizador.sincronizar();

        assertEquals(1, r.bancariasNuevas());
        assertEquals(1, r.contablesNuevas());
        assertEquals(1, r.circuitosNuevos());
        assertTrue(r.sincronizado());
        assertEquals(1, bancoRepo.count());
        assertEquals(1, contableRepo.count());
        assertEquals(1, circuitoRepo.count());

        CuentaBancaria cuenta = bancoRepo.findByXubioId("x-1").orElseThrow();
        assertEquals("Cuenta Corriente 123", cuenta.getNombre());
        assertEquals("Galicia", cuenta.getBanco());
        assertEquals("0000003100000003079083", cuenta.getCbu());
    }

    @Test
    @DisplayName("Sincronizar dos veces no duplica: el id de Xubio es la clave")
    void sincronizarDosVecesNoDuplica() {
        fuente.reemplazar(new Catalogos(
                List.of(new ItemCatalogo("x-1", "Cuenta Corriente 123", "cbu-1", "Galicia")),
                List.of(new ItemCatalogo("c-1", "Clientes", "1.1.01.001", null)),
                List.of(new ItemCatalogo("k-1", "Ventas", null, null))));

        sincronizador.sincronizar();
        SincronizadorCatalogos.Resultado segunda = sincronizador.sincronizar();

        assertEquals(1, bancoRepo.count(), "no puede quedar la misma cuenta dos veces");
        assertEquals(1, contableRepo.count());
        assertEquals(1, circuitoRepo.count());
        assertEquals(0, segunda.bancariasNuevas(), "la segunda vuelta no inserta nada nuevo");
        assertEquals(1, segunda.bancosActualizados());
    }

    @Test
    @DisplayName("Refresca el nombre si en Xubio cambio, sin tocar el CBU")
    void refrescaNombreYNoElCbu() {
        bancoRepo.saveAndFlush(new CuentaBancaria("Nombre viejo", "Galicia", "cbu-original", "x-1"));

        fuente.reemplazar(new Catalogos(
                List.of(new ItemCatalogo("x-1", "Nombre nuevo", null, "Galicia")),
                List.of(), List.of()));
        sincronizador.sincronizar();

        CuentaBancaria cuenta = bancoRepo.findByXubioId("x-1").orElseThrow();
        assertEquals("Nombre nuevo", cuenta.getNombre());
        // El CBU no vino en el catalogo. Ponerlo en null perderia un dato que ya
        // teniamos, y cambiarlo dejaria el historico apuntando a un CBU que ya
        // no es el de esa cuenta.
        assertEquals("cbu-original", cuenta.getCbu());
    }

    @Test
    @DisplayName("Una cuenta que desaparece de Xubio NO se borra")
    void noBorraLoQueDesaparece() {
        fuente.reemplazar(new Catalogos(
                List.of(new ItemCatalogo("x-1", "Cuenta 1", "cbu-1", "Galicia"),
                        new ItemCatalogo("x-2", "Cuenta 2", "cbu-2", "Nacion")),
                List.of(), List.of()));
        sincronizador.sincronizar();
        assertEquals(2, bancoRepo.count());

        // Segunda vuelta: Xubio ya no manda la cuenta x-2.
        fuente.reemplazar(new Catalogos(
                List.of(new ItemCatalogo("x-1", "Cuenta 1", "cbu-1", "Galicia")),
                List.of(), List.of()));
        sincronizador.sincronizar();

        assertEquals(2, bancoRepo.count(),
                "borrar la cuenta dejaria sus movimientos historicos apuntando al vacio");
        assertTrue(bancoRepo.findByXubioId("x-2").isPresent(),
                "la cuenta sigue existiendo, aunque Xubio ya no la devuelva");
    }

    @Test
    @DisplayName("Las filas sin id se descartan y se cuentan, no se inventan")
    void descartaFilasSinId() {
        fuente.reemplazar(new Catalogos(
                List.of(new ItemCatalogo("x-1", "Cuenta 1", "cbu-1", "Galicia"),
                        new ItemCatalogo(null, "Sin id", "cbu-2", "Galicia"),
                        new ItemCatalogo("   ", "Id en blanco", "cbu-3", "Galicia")),
                List.of(),
                List.of(new ItemCatalogo("", " Circuito sin id", null, null))));

        SincronizadorCatalogos.Resultado r = sincronizador.sincronizar();

        assertEquals(1, bancoRepo.count());
        assertEquals(0, circuitoRepo.count());
        assertEquals(3, r.filasSinId(), "las tres filas sin id util tienen que quedar contadas");
    }

    @Test
    @DisplayName("Si falta el nombre o el codigo, se completa con algo estable")
    void completaFaltantes() {
        fuente.reemplazar(new Catalogos(
                List.of(new ItemCatalogo("x-9", null, null, null)),
                List.of(new ItemCatalogo("c-9", "Clientes", null, null)),
                List.of(new ItemCatalogo("k-9", null, null, null))));

        sincronizador.sincronizar();

        CuentaBancaria banco = bancoRepo.findByXubioId("x-9").orElseThrow();
        // Sin CBU no se rompe: la columna paso a ser nullable justamente para esto.
        assertNull(banco.getCbu());
        assertNotNull(banco.getNombre(), "nombre es NOT NULL, tiene que tener algo");
        assertNotNull(banco.getBanco(), "banco es NOT NULL, tiene que tener algo");

        // El codigo contable es NOT NULL y no vino: se usa el id de Xubio, que es
        // feo pero unico y estable. Un codigo inventado seria peor.
        assertEquals("c-9", contableRepo.findByXubioId("c-9").orElseThrow().getCodigo());
        assertNotNull(circuitoRepo.findByXubioId("k-9").orElseThrow().getNombre());
    }

    @Test
    @DisplayName("Con la fuente apagada no se inserta nada")
    void fuenteApagadaNoHaceNada() {
        fuente.reemplazar(new Catalogos(
                List.of(new ItemCatalogo("x-1", "Cuenta", "cbu-1", "Galicia")),
                List.of(), List.of()));
        fuente.apagar();

        SincronizadorCatalogos.Resultado r = sincronizador.sincronizar();

        assertEquals(0, bancoRepo.count());
        assertEquals(0, r.bancariasNuevas());
        assertTrue(!r.sincronizado(), "con la fuente apagada no se sincronizo nada");
    }

    @Test
    @DisplayName("Un catalogo vacio de verdad NO borra lo que ya estaba")
    void catalogoVacioNoBorra() {
        bancoRepo.saveAndFlush(new CuentaBancaria("Cuenta existente", "Galicia", "cbu-1"));
        fuente.reemplazar(new Catalogos(List.of(), List.of(), List.of()));

        SincronizadorCatalogos.Resultado r = sincronizador.sincronizar();

        assertEquals(1, bancoRepo.count(),
                "un catalogo vacio se parece a un fallo de filtrado; vaciar la pantalla seria lo peor");
        assertEquals(0, r.bancariasNuevas());
        assertTrue(r.sincronizado(), "Xubio contesto bien, solo que sin datos: eso no es un fallo");
    }

    @Test
    @DisplayName("Con la fuente prendida, el arranque sabe que hay que sincronizar")
    void fuentePrendidaSeSabe() {
        // De esto depende `DataSeeder`: si la fuente esta prendida, NO siembra
        // cuentas inventadas, y sin cuentas los movimientos de arranque no tienen
        // donde colgarse. Si `sincronizado()` mintiera, la pantalla se llenaria de
        // cuentas de la semilla al lado de las de Xubio, sin avisar.
        fuente.reemplazar(new Catalogos(
                List.of(new ItemCatalogo("x-1", "Cuenta", "cbu-1", "Galicia")),
                List.of(), List.of()));

        assertTrue(sincronizador.sincronizado());

        fuente.apagar();
        assertFalse(sincronizador.sincronizado());
    }

    /** Catalogo de mentira: devuelve lo que el test le pida y no toca la red. */
    static class CatalogoFalso implements CatalogoXubio {

        private Catalogos catalogos = new Catalogos(List.of(), List.of(), List.of());
        private boolean prendido;

        /**
         * Carga el catalogo Y deja la fuente prendida.
         *
         * Las dos cosas juntas a proposito: si `reemplazar` no prendiera, cada
         * test de sincronizacion tendria que acordarse de prenderla, y el que se
         * olvide no falla por una razon que dice "no inserta", sino porque se
         * pidio el catalogo a una fuente apagada. Un test que pasa por la razon
         * equivocada no sirve para nada. Apagarla es entonces una accion propia
         * (`apagar`), que es justo lo que prueba el caso de la fuente apagada.
         */
        void reemplazar(Catalogos c) {
            this.catalogos = c;
            this.prendido = true;
        }

        void apagar() {
            this.prendido = false;
        }

        @Override
        public Catalogos consultar() {
            return catalogos;
        }

        @Override
        public boolean habilitado() {
            return prendido;
        }

        @Override
        public String nombre() {
            return "Xubio (falso)";
        }
    }

    /**
     * Registra el catalogo falso con `@Primary` para que gane contra el
     * `XubioCliente` de verdad, que tambien es un `CatalogoXubio`. Sin esto el
     * test arrancaria el cliente HTTP real, que con `habilitado: false` no
     * llamaria a nadie pero dejaria el bean ahi como un recordatorio incomodo de
     * que el test no esta probando lo que dice.
     *
     * `@TestConfiguration` y NO `@Configuration`: una clase de configuracion
     * anidada se toma como fuente primaria de la app, y `@DataJpaTest` deja de
     * encontrar los paquetes de auto-configuracion y revienta con "Unable to
     * retrieve @EnableAutoConfiguration base packages". `@TestConfiguration`
     * existe justamente para esto: se registra sin ocupar el lugar de la app.
     */
    @TestConfiguration
    static class ConfigCatalogoFalso {

        @Bean
        @Primary
        CatalogoFalso catalogoFalso() {
            return new CatalogoFalso();
        }
    }
}
