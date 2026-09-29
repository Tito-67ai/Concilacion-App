package com.conciliacion.application.catalogo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Como quedo la ultima lectura de catalogos, para poder responder por ella.
 *
 * ── POR QUE HACE FALTA ALGO ASI ───────────────────────────────────────────────
 *
 * Porque la sincronizacion corre UNA vez, al arrancar. Si despues el frontend
 * pidiera las opciones, no tendria de donde enterarse de si Xubio respondio o
 * no: la base tiene datos de todos modos (la semilla, o los de un sync anterior)
 * y responder 200 con ellos seria mentir.
 *
 * Y esa mentira es el caso que mas dano hace en esta pantalla. Con Xubio prendido
 * y roto, la base tiene las dos cuentas de la semilla, el desplegable se ve
 * perfecto, y el usuario elige "Cuenta Corriente / Banco Galicia" creyendo que
 * es una cuenta de la empresa. No hay ningun signo de que algo este mal. Para el
 * usuario, la app esta funcionando.
 *
 * Con este estado, `/filtros/opciones` puede decir la verdad: "no pudimos leer
 * los catalogos, y esto es por que", en vez de servirle un desplegable lleno de
 * cuentas inventadas.
 *
 * ── POR QUE NO SE PREGUNTA A XUBIO EN CADA PEDIDO ──────────────────────────────
 *
 * Seria lo mas simple y lo peor: cada vez que el usuario recarga la pantalla
 * habria tres llamadas de red, y con Xubio caido la pantalla tardaria el timeout
 * en cada carga. El filtro se consulta al entrar a la pantalla, no en cada
 * keystroke, pero igual: una pantalla que tarda 10 segundos en cargar porque el
 * sistema de afuera esta caido es una pantalla rota de otra manera.
 *
 * Con el estado guardado, el error es instantaneo y se puede hacer la
 * sincronizacion periodica despues sin cambiar nada de aca.
 *
 * ── POR QUE NO ES UNA EXCEPCION ────────────────────────────────────────────────
 *
 * Porque no es una excepcion: es un estado. La app arranca con Xubio prendido y
 * roto TODAS las veces, y arrancar bien es lo correcto. Si esto fuera una
 * excepcion, el arranque tendria que tragarsela (que es lo que hace el runner) y
 * el estado se perderia igual. Guardarlo es lo que hace que el error sobreviva
 * hasta la pantalla, que es donde sirve.
 */
@Component
public class EstadoCatalogos {

    private static final Logger log = LoggerFactory.getLogger(EstadoCatalogos.class);

    /**
     * Motivo del fallo, o null si no hay.
     *
     * `volatile` porque lo escribe el hilo de arranque y lo leen los hilos de
     * pedido. Sin `volatile` un hilo puede ver el `disponible` nuevo y el motivo
     * viejo, que es la combinacion que produce "error generico" en el lugar donde
     * hacia falta el detalle.
     */
    private volatile String motivo;

    /**
     * Si hay un problema para mostrar.
     *
     * Se devuelve `true` UNICAMENTE cuando la fuente esta prendida y no se pudo
     * leer. Con la fuente apagada no hay problema: es una decision, y la pantalla
     * tiene que andar con lo que hay en la base.
     */
    public boolean hayProblema() {
        return motivo != null;
    }

    /** El motivo, ya listo para mandar en el `detalle` de la respuesta. */
    public String motivo() {
        return motivo;
    }

    public void marcarFallo(String causa) {
        this.motivo = causa;
        log.warn("Estado de los catalogos: problema. {}", causa);
    }

    public void marcarOk() {
        this.motivo = null;
    }

    /**
     * La fuente esta apagada.
     *
     * Se limpia el estado a proposito: si antes hubo un fallo y despues se apaga
     * la fuente, la app tiene que dejar de avisar. El aviso era sobre una fuente
     * que ya no esta.
     */
    public void marcarNoAplicable() {
        this.motivo = null;
    }
}
