package com.conciliacion.application.banco;

import com.conciliacion.domain.model.CuentaBancaria;
import com.conciliacion.domain.model.OrigenMovimiento;

import java.util.List;

/**
 * El contrato que tiene que cumplir CUALQUIER fuente de movimientos bancarios:
 * un CSV que sube el usuario, la API de Galicia, la de Santander, la de Xubio.
 *
 * Es una interfaz, no una clase, a proposito: cuando llegue el banco nuevo se agrega
 * UNA clase y no se toca nada mas. La deduplicacion, el registro de la corrida y el
 * link a la cuenta los hace la ingesta, no el conector, asi que todos se benefician
 * de lo mismo.
 *
 * Que debe cumplir la implementacion:
 *
 *  1. `codigo()` es estable y en MAYUSCULAS. Es lo que queda guardado en
 *     importacion_bancaria.extractor, asi que no puede cambiarse sin perder el
 *     historial de las corridas viejas.
 *  2. `extraer` NO toca la base. Solo arma MovimientoBancoCrudo y devuelve. Si un
 *     banco responde paginado, la implementacion pagina internamente.
 *  3. `extraer` NO filtra por el estado de conciliacion. Reimportar un rango ya
 *     conciliado tiene que devolver las mismas filas: si no, la deduplicacion por
 *     comprobante las reconoce y las descarta.
 *  4. Si la fuente no trae comprobante, devolver null. La ingesta no deduplica, pero
 *     sigue funcionando; solo se pierde la idempotencia de esa fuente.
 *
 * Para una API real, el siguiente extractor va a necesitar:
 *  - token que se renueva solo (los de banco expiran en minutos),
 *  - reintento con backoff ante 429/5xx,
 *  - timeouts por pagina,
 *  - credenciales FUERA de application.yml, idealmente en un secret manager.
 */
public interface ExtractorBancario {

    /** Identificador estable de la fuente. Ej: DEMO, CSV, GALICIA_API, XUBIO_API. */
    String codigo();

    /** Texto para mostrar en la UI. */
    String descripcion();

    /**
     * De que clase de fuente es, para estamparlo en cada fila. Ej: CSV, API_BANCARIA.
     * Es la distincion entre "lo bajo una API" y "lo escribio alguien", que no es lo
     * mismo aunque hoy las dos cosas den el mismo resultado.
     */
    OrigenMovimiento origen();

    /** Si la fuente acepta un archivo subido por el usuario (solo CSV). */
    default boolean aceptaArchivo() { return false; }

    /**
     * Si la fuente puede correr sola, pidiéndole los datos a un sistema externo.
     * El extractor de archivo no puede: sin archivo no hay nada que pedirle.
     */
    default boolean puedeEjecutarseSolo() { return true; }

    /**
     * Baja los movimientos de una cuenta en un rango. Lanza excepcion si la fuente no
     * responde (red, auth, rate limit): la ingesta la registra como corrida fallida
     * en vez de tragarsela.
     */
    List<MovimientoBancoCrudo> extraer(CuentaBancaria cuenta, RangoFechas rango);

    /** Igual que `extraer` pero desde un archivo que sube el usuario. */
    default List<MovimientoBancoCrudo> extraerDeArchivo(CuentaBancaria cuenta,
                                                         RangoFechas rango,
                                                         byte[] contenido) {
        throw new UnsupportedOperationException(codigo() + " no lee archivos subidos por el usuario");
    }
}
