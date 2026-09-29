package com.conciliacion.application.catalogo;

import com.conciliacion.application.ConciliacionService;
import com.conciliacion.application.OpcionesFiltro;
import com.conciliacion.domain.model.CuentaBancaria;
import com.conciliacion.infrastructure.persistence.CircuitoContableRepository;
import com.conciliacion.infrastructure.persistence.ConciliacionRepository;
import com.conciliacion.infrastructure.persistence.CuentaBancariaRepository;
import com.conciliacion.infrastructure.persistence.CuentaContableRepository;
import com.conciliacion.infrastructure.persistence.MovimientoBancarioRepository;
import com.conciliacion.infrastructure.persistence.MovimientoContableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Que las opciones del filtro no sirvan datos que no son de fiar.
 *
 * ── POR QUE ESTE CASO MERECE UN ARCHIVO PROPIO ─────────────────────────────────
 *
 * Porque es la decision de la que mas se puede dudar sin notarlo. Cuando hay un
 * problema con los catalogos, la base TIENE algo para mostrar (en desarrollo, la
 * semilla), asi que devolver 200 con esas cuentas es facil, no tira ningun error
 * y la app "anda". El problema es que el usuario no tiene ninguna forma de saber
 * que esas cuentas no son de la empresa: elige "Cuenta Corriente / Banco Galicia"
 * creyendo que es real, y para el sistema es una cuenta de verdad.
 *
 * Estos tests fijan que la respuesta es 503 y no 200 con datos dudosos. Si alguien
 * lo cambia para "que la pantalla no se caiga", el fallo queda aca escrito.
 *
 * ── POR QUE MOCKS Y NO @DataJpaTest ────────────────────────────────────────────
 *
 * Porque lo que se prueba es una decision, no una query. La decision es "si hay
 * problema, no se lee la base". Eso se verifica viendo que los repositorios no se
 * tocan, y con una base de verdad habria que sembrar filas para comprobar que
 * NO aparecen en la respuesta, que es mas lento y prueba menos.
 */
class OpcionesConCatalogosRotosTest {

    private CuentaBancariaRepository bancos;
    private CuentaContableRepository contables;
    private CircuitoContableRepository circuitos;
    private EstadoCatalogos estado;
    private ConciliacionService service;

    @BeforeEach
    void armar() {
        bancos = mock(CuentaBancariaRepository.class);
        contables = mock(CuentaContableRepository.class);
        circuitos = mock(CircuitoContableRepository.class);
        estado = new EstadoCatalogos();
        service = new ConciliacionService(
                mock(MovimientoBancarioRepository.class),
                mock(MovimientoContableRepository.class),
                mock(ConciliacionRepository.class),
                bancos, contables, circuitos, estado);
    }

    @Test
    @DisplayName("Sin problema, devuelve las cuentas de la base")
    void sinProblemaDevuelveLaBase() {
        when(bancos.findAllByOrderByNombreAsc())
                .thenReturn(List.of(new CuentaBancaria("Cuenta Corriente", "Galicia", "cbu-1")));
        when(contables.findAllByOrderByCodigoAsc()).thenReturn(List.of());
        when(circuitos.findAllByOrderByNombreAsc()).thenReturn(List.of());

        OpcionesFiltro opciones = service.opciones();

        assertEquals(1, opciones.cuentasBancarias().size());
        assertEquals("Galicia", opciones.cuentasBancarias().get(0).getBanco());
    }

    @Test
    @DisplayName("Con la fuente caida, NO se sirven las cuentas de la base")
    void conProblemaNoSirveLaBase() {
        estado.marcarFallo("Xubio no respondio a tiempo (10 s).", true);

        CatalogoNoDisponibleException e = assertThrows(CatalogoNoDisponibleException.class,
                () -> service.opciones());

        // El motivo de Xubio tiene que llegar entero a la pantalla, no recortado a
        // "algo fallo": es lo unico que le dice al usuario que hacer.
        assertTrue(e.getMessage().contains("no respondio a tiempo"), e.getMessage());
    }

    @Test
    @DisplayName("Un timeout se puede reintentar; unas credenciales malas, no")
    void elFlagDeReintentarVieneDelCliente() {
        estado.marcarFallo("Xubio no respondio a tiempo (10 s).", true);
        CatalogoNoDisponibleException timeout = assertThrows(CatalogoNoDisponibleException.class,
                () -> service.opciones());
        assertTrue(timeout.esReintentable(), "un timeout se cae solo");

        estado.marcarFallo("Xubio rechazo el pedido de token (400). invalid_client", false);
        CatalogoNoDisponibleException credenciales = assertThrows(CatalogoNoDisponibleException.class,
                () -> service.opciones());
        // Este es el que importaba: antes el flag salia siempre en `true`, asi que un
        // boton de "reintentar" con credenciales malas prometia algo que no pasa.
        assertFalse(credenciales.esReintentable(),
                "con credenciales malas, reintentar devuelve exactamente lo mismo");
    }

    @Test
    @DisplayName("Con la fuente caida, los repositorios ni se tocan")
    void conProblemaNoLeeLosRepos() {
        estado.marcarFallo("faltan las credenciales", false);

        assertThrows(CatalogoNoDisponibleException.class, () -> service.opciones());

        // Si se leyeran y se arreglara despues, el codigo volveria a ofrecer
        // cuentas inventadas. Esta linea es la que deja escrito que no.
        verifyNoInteractions(bancos, contables, circuitos);
    }

    @Test
    @DisplayName("El aviso se levanta y se baja con el estado de la sincronizacion")
    void elAvisoAcompanaAlEstado() {
        assertFalse(estado.hayProblema());

        estado.marcarFallo("faltan las credenciales", false);
        assertTrue(estado.hayProblema());
        assertEquals("faltan las credenciales", estado.motivo());

        estado.marcarOk();
        assertFalse(estado.hayProblema(), "un sync bueno limpia el estado");

        estado.marcarFallo("otro fallo", true);
        estado.marcarNoAplicable();
        assertFalse(estado.hayProblema(),
                "apagar la fuente limpia el aviso: ya no hay nada que avisar");
    }
}
