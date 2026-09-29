package com.conciliacion.infrastructure.config;

import com.conciliacion.application.catalogo.CatalogoNoDisponibleException;
import com.conciliacion.application.catalogo.EstadoCatalogos;
import com.conciliacion.application.catalogo.SincronizadorCatalogos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Baja los catalogos de Xubio una vez al arrancar.
 *
 * ── POR QUE UN RUNNER Y NO UN @Scheduled ──────────────────────────────────────
 *
 * Porque al arrancar la base esta vacia (H2 es create-drop), y con la base vacia
 * los desplegables del filtro no tienen nada que mostrar. Si la sincronizacion
 * fuera periodica, habria una ventana de arranque con la pantalla inservible, y
 * en desarrollo esa ventana es casi siempre.
 *
 * Un `@Scheduled` tiene sentido despues, para el caso real: en produccion la base
 * no se recrea en cada despliegue, y el catalogo de Xubio cambia (abren una
 * cuenta, cierran un circuito) sin que la app se entere. Ese refresh periodico
 * todavia no esta, y es un `@Scheduled` con su @Value de periodo, no otra cosa.
 *
 * ── POR QUE UN FALLO NO TIRA LA APP ───────────────────────────────────────────
 *
 * Porque la app tiene que arrancar igual. Si Xubio esta caido y el arranque
 * falla, no hay ni pantalla ni log ni forma de entrar a arreglarlo, y el unico
 * sintoma es un contenedor que no levanta. Aca el fallo se loguea fuerte y se
 * sigue: el filtro quedara con lo que haya en la base, y si no hay nada, la
 * pantalla muestra el error cuando se piden las opciones.
 *
 * ── POR QUE ESTE VA ANTES QUE LA SEMILLA ──────────────────────────────────────
 *
 * Por el `@Order(0)`. La semilla cuelga sus movimientos de cuentas que existen, y
 * si se sembrara primero, sus cuentas falsas ya estarian en el desplegable al
 * lado de las que trajo Xubio. La pantalla tendria cuatro cuentas bancarias donde
 * deberia tener dos reales, y dos de ellas no las tiene la empresa.
 */
@Component
@Order(0)
public class SincronizadorCatalogosArranque implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SincronizadorCatalogosArranque.class);

    private final SincronizadorCatalogos sincronizador;
    private final EstadoCatalogos estado;

    public SincronizadorCatalogosArranque(SincronizadorCatalogos sincronizador, EstadoCatalogos estado) {
        this.sincronizador = sincronizador;
        this.estado = estado;
    }

    @Override
    public void run(String... args) {
        // Sin fuente prendida no hay nada que sincronizar. Se marca "no aplica" y
        // se sale: la pantalla se arma con lo que haya en la base, y eso es lo
        // correcto en una maquina sin Xubio.
        if (!sincronizador.sincronizado()) {
            estado.marcarNoAplicable();
            log.debug("Ninguna fuente de catalogos prendida: no hay nada que sincronizar.");
            return;
        }

        try {
            sincronizador.sincronizar();
            estado.marcarOk();
        } catch (CatalogoNoDisponibleException e) {
            // Se guarda el motivo para que la pantalla lo muestre despues. El log
            // es para el que esta mirando la consola; el estado es para el usuario.
            estado.marcarFallo(e.getMessage());
            log.error("No se pudieron sincronizar los catalogos al arrancar: {}", e.getMessage(), e);
            log.error("La app sigue arrancando, pero los desplegables del filtro van a mostrar este "
                    + "motivo en vez de una lista de cuentas. La app NO va a sembrar cuentas propias, "
                    + "para que no aparezcan cuentas inventadas al lado de las de la empresa.");
        } catch (RuntimeException e) {
            // Un fallo propio (no de la API): un UNIQUE de CBU repetido, un tipo de
            // dato que no entra en la columna. Tambien se registra y se sigue.
            estado.marcarFallo("No se pudieron sincronizar los catalogos: " + e.getMessage());
            log.error("Fallo inesperado al sincronizar los catalogos: {}", e.getMessage(), e);
        }
    }
}
