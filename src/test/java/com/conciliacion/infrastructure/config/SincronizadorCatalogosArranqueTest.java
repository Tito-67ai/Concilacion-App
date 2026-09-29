package com.conciliacion.infrastructure.config;

import com.conciliacion.application.catalogo.CatalogoNoDisponibleException;
import com.conciliacion.application.catalogo.Catalogos;
import com.conciliacion.application.catalogo.EstadoCatalogos;
import com.conciliacion.application.catalogo.ItemCatalogo;
import com.conciliacion.application.catalogo.SincronizadorCatalogos;
import com.conciliacion.infrastructure.persistence.CircuitoContableRepository;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Que el arranque de la sincronizacion este cableado y no solo compilando.
 *
 * ── POR QUE ESTE TEST EXISTE ──────────────────────────────────────────────────
 *
 * Porque aqui ya se pasaron dos bugs que ninguna otra prueba ve. Los dos en el
 * mismo archivo, los dos por el mismo motivo: el runner se escribio, se compilo,
 * y no hacia nada. Un `sincronizar()` que nunca se llama deja el estado en "todo
 * bien" porque no hay nada que falle, y la app muestra las cuentas de la semilla
 * sin avisar que Xubio no dio una sola.
 *
 * Un test que solo mira el codigo no lo encuentra. Uno que lo corre lo encuentra
 * siempre, y mas barato.
 *
 * Los dos catalogos que se guardan son dos, no tres: las cuentas bancarias no
 * entran por aca (Xubio no las expone). Si este test espera tres, esta probando
 * una version vieja.
 */
class SincronizadorCatalogosArranqueTest {

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
                    List.of(new ItemCatalogo("c-1", "Clientes", "1.1.01.001")),
                    List.of(new ItemCatalogo("k-1", "Ventas", "V")));
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
        when(contables.save(any())).thenAnswer(i -> {
            guardados.add("contable");
            return i.getArgument(0);
        });
        when(circuitos.save(any())).thenAnswer(i -> {
            guardados.add("circuito");
            return i.getArgument(0);
        });
        return new SincronizadorCatalogosArranque(
                new SincronizadorCatalogos(fuente, contables, circuitos), estado);
    }

    @Test
    @DisplayName("Con la fuente apagada no sincroniza y no deja ningun aviso")
    void fuenteApagada() {
        Fuente fuente = new Fuente();
        EstadoCatalogos estado = new EstadoCatalogos();

        armar(fuente, estado).run();

        assertTrue(guardados.isEmpty(), "no se inserta nada con la fuente apagada");
        assertFalse(estado.hayProblema(), "una fuente apagada es una decision, no un problema");
        verify(contables, times(0)).save(any());
    }

    @Test
    @DisplayName("Con la fuente prendida y andando, sincroniza y queda limpio")
    void sincronizaYQuedaLimpio() {
        Fuente fuente = new Fuente();
        fuente.prendido = true;
        EstadoCatalogos estado = new EstadoCatalogos();

        armar(fuente, estado).run();

        // Las dos filas del catalogo. Si el runner no llamara a `sincronizar()`,
        // esto seria vacio y la prueba no necesitaria ningun mock extra.
        assertEquals(List.of("contable", "circuito"), guardados);
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
        // Un UNIQUE de codigo repetido, por ejemplo: no es una excepcion de catalogo,
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

        // Un guardado por catalogo, y NO dos vueltas de los dos.
        verify(contables, times(1)).save(any());
        verify(circuitos, times(1)).save(any());
    }
}
