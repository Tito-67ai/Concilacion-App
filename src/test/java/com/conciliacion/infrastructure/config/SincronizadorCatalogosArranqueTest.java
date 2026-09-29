package com.conciliacion.infrastructure.config;

import com.conciliacion.application.catalogo.CatalogoNoDisponibleException;
import com.conciliacion.application.catalogo.Catalogos;
import com.conciliacion.application.catalogo.EstadoCatalogos;
import com.conciliacion.application.catalogo.ItemCatalogo;
import com.conciliacion.application.catalogo.SincronizadorCatalogos;
import com.conciliacion.infrastructure.persistence.CircuitoContableRepository;
import com.conciliacion.infrastructure.persistence.CuentaBancariaRepository;
import com.conciliacion.infrastructure.persistence.CuentaContableRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Que el arranque sincronice los catalogos y deje el estado como corresponde.
 *
 * ── POR QUE HACE FALTA UN ARCHIVO PROPIO PARA ESTO ─────────────────────────────
 *
 * Porque aca pasaron dos bugs seguidos que ninguna otra pruebacia agarro, y los
 * dos eran graves:
 *
 *  1. El runner pedia el estado (`sincronizado()`) pero nunca llamaba a
 *     `sincronizar()`. La app arrancaba, no sincronizaba nada, y marcaba el
 *     estado como "todo bien". Con Xubio prendido y andando, la pantalla no
 *     mostraba ninguna cuenta de Xubio y no habia ningun error visible.
 *  2. Con la fuente prendida y fallando, el estado quedaba limpio y
 *     `/filtros/opciones` servia las cuentas de la semilla con un 200.
 *
 * Los dos son fallos de ARRANQUE: son componentes que compilan, que se pueden
 * inyectar y que no se rompen. Un test de `SincronizadorCatalogos` dice
 * que sincroniza bien; uno de `ConciliacionService` dice que tira 503; ninguno de
 * los dos dice que esten conectados. Este es el test que dice eso.
 *
 * ── QUE CASOS CUBRE ───────────────────────────────────────────────────────────
 *
 *  1. Fuente apagada: no se sincroniza y el estado queda limpio. La pantalla tiene
 *     que andar normal, sin avisos.
 *  2. Fuente prendida y que anda: se sincroniza UNA vez y el estado queda limpio.
 *  3. Fuente prendida y que falla: el estado queda con el motivo, para que la
 *     pantalla lo muestre en vez de servir cuentas de la semilla.
 *  4. Un fallo propio (no de la API) tambien deja el estado marcado, no se pierde
 *     en el log.
 */
class SincronizadorCatalogosArranqueTest {

    private final CuentaBancariaRepository bancos = mock(CuentaBancariaRepository.class);
    private final CuentaContableRepository contables = mock(CuentaContableRepository.class);
    private final CircuitoContableRepository circuitos = mock(CircuitoContableRepository.class);
    private final List<String> guardados = new ArrayList<>();

    /** Fuente de mentira que se puede prender, apagar y hacer fallar. */
    private class Fuente implements com.conciliacion.application.catalogo.CatalogoXubio {

        boolean prendido;
        boolean debeFallar;
        String motivo = "Xubio no respondio a tiempo (10 s).";

        @Override
        public Catalogos consultar() {
            if (debeFallar) {
                throw new CatalogoNoDisponibleException(motivo, true);
            }
            return new Catalogos(
                    List.of(new ItemCatalogo("x-1", "Cuenta", "cbu-1", "Galicia")),
                    List.of(new ItemCatalogo("c-1", "Clientes", "1.1.01.001", null)),
                    List.of(new ItemCatalogo("k-1", "Ventas", null, null)));
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

    private SincronizadorCatalogosArranque armar(Fuente fuente, EstadoCatalogos estado) {
        when(bancos.save(any())).thenAnswer(i -> {
            guardados.add("banco");
            return i.getArgument(0);
        });
        when(contables.save(any())).thenAnswer(i -> {
            guardados.add("contable");
            return i.getArgument(0);
        });
        when(circuitos.save(any())).thenAnswer(i -> {
            guardados.add("circuito");
            return i.getArgument(0);
        });
        return new SincronizadorCatalogosArranque(
                new SincronizadorCatalogos(fuente, bancos, contables, circuitos), estado);
    }

    @Test
    @DisplayName("Con la fuente apagada no sincroniza y no deja ningun aviso")
    void fuenteApagada() {
        Fuente fuente = new Fuente();
        EstadoCatalogos estado = new EstadoCatalogos();

        armar(fuente, estado).run();

        assertTrue(guardados.isEmpty(), "no se inserta nada con la fuente apagada");
        assertFalse(estado.hayProblema(), "una fuente apagada es una decision, no un problema");
        verify(bancos, never()).save(any());
    }

    @Test
    @DisplayName("Con la fuente prendida y andando, sincroniza y queda limpio")
    void sincronizaYQuedaLimpio() {
        Fuente fuente = new Fuente();
        fuente.prendido = true;
        EstadoCatalogos estado = new EstadoCatalogos();

        armar(fuente, estado).run();

        // Las tres filas del catalogo. Si el runner no llamara a `sincronizar()`,
        // esto seria vacio y la prueba no necesitaria ningun mock extra.
        assertEquals(List.of("banco", "contable", "circuito"), guardados);
        assertFalse(estado.hayProblema());
    }

    @Test
    @DisplayName("Con la fuente prendida y fallando, el motivo queda para la pantalla")
    void dejaElMotivoParaLaPantalla() {
        Fuente fuente = new Fuente();
        fuente.prendido = true;
        fuente.debeFallar = true;
        EstadoCatalogos estado = new EstadoCatalogos();

        // No tira: el arranque tiene que terminar bien o la app no levanta y el
        // unico sintoma es un contenedor que no arranca.
        armar(fuente, estado).run();

        assertTrue(estado.hayProblema());
        assertEquals("Xubio no respondio a tiempo (10 s).", estado.motivo());
        assertTrue(guardados.isEmpty(), "un fallo no inserta nada a medias");
    }

    @Test
    @DisplayName("Un fallo propio tambien deja el estado marcado, no se pierde en el log")
    void falloPropioTambienSeMarca() {
        // Un UNIQUE de CBU repetido, por ejemplo: no es una excepcion de catalogo,
        // es un fallo nuestro. Si este camino no marcara el estado, la pantalla
        // serviria las cuentas de la semilla pensando que todo esta bien.
        Fuente fuente = new Fuente() {
            @Override
            public Catalogos consultar() {
                throw new IllegalStateException("CBU duplicado en el catalogo");
            }
        };
        fuente.prendido = true;
        EstadoCatalogos estado = new EstadoCatalogos();

        armar(fuente, estado).run();

        assertTrue(estado.hayProblema());
        assertTrue(estado.motivo().contains("CBU duplicado"), estado.motivo());
    }

    @Test
    @DisplayName("Sincroniza una sola vez, no una por catalogo")
    void sincronizaUnaSolaVez() {
        Fuente fuente = new Fuente();
        fuente.prendido = true;

        armar(fuente, new EstadoCatalogos()).run();

        // Tres guardados, uno por catalogo, y NO tres vueltas de los tres.
        verify(bancos, times(1)).save(any());
        verify(contables, times(1)).save(any());
        verify(circuitos, times(1)).save(any());
    }
}
