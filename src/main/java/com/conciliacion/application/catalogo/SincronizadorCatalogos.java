package com.conciliacion.application.catalogo;

import com.conciliacion.domain.model.CircuitoContable;
import com.conciliacion.domain.model.CuentaContable;
import com.conciliacion.infrastructure.persistence.CircuitoContableRepository;
import com.conciliacion.infrastructure.persistence.CuentaContableRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Pasa los catalogos de Xubio a nuestras tablas.
 *
 * ── QUE CATALOGOS SINCRONIZA, Y POR QUE NO EL TERCERO ─────────────────────────
 *
 * Las cuentas contables y los circuitos. Las cuentas bancarias NO, porque la API
 * de Xubio no las expone (ver `Catalogos`), y porque son el unico catalogo del
 * filtro cuyo dato clave es el CBU, que no esta en ninguna respuesta de Xubio.
 *
 * ── POR QUE COPIAR Y NO USAR LOS ID DE XUBIO DIRECTAMENTE ─────────────────────
 *
 * La idea era "que los filtros timen los datos directamente de Xubio", y esta es
 * la parte donde conviene separar lo que se pide de como se guarda.
 *
 * Los movimientos de la base cuelgan de estas cuentas por clave foranea: cada
 * movimiento contable tiene su `cuenta_contable_id` y su `circuito_id`. Si el id
 * de la cuenta fuera el string de Xubio, cada movimiento, cada conciliacion, cada
 * query y cada parametro de filtrado de la pantalla tendria que arrastrar el id
 * del tercero. Y el dia que se cambie de sistema, o que se combine con otro, no
 * habria forma de volver atras: los datos historicos quedan atados a un id que
 * solo existe afuera.
 *
 * Copiando, el id externo queda en una columna (`xubio_id`) que es un dato MAS de
 * la cuenta, no su identidad. El id local sigue siendo nuestro, las FK siguen
 * funcionando, y si manana la fuente es otro sistema, esta columna pasa a
 * guardar el id de ese y no hay que tocar ni una consulta.
 *
 * ── QUE PASA CON LO QUE NO SE SINCRONIZA ──────────────────────────────────────
 *
 * NO se borra. Si una cuenta desaparece del catalogo de Xubio queda en la base,
 * con sus movimientos y su conciliacion historica. Borrarla dejaria movimientos
 * apuntando a una FK inexistente, o habria que cascading y perder el historico.
 * Con los datos de conciliacion, "la cuenta ya no esta" casi siempre significa
 * "la API no la devolvio en esta llamada", que no es lo mismo.
 *
 * Por eso el filtro se arma con TODAS las cuentas de la base, no con las que
 * estan en el catalogo. Un movimiento de mayo tiene que seguir siendo
 * filtrable en junio, y para eso la cuenta tiene que seguir en el desplegable.
 */
@Service
public class SincronizadorCatalogos {

    private static final Logger log = LoggerFactory.getLogger(SincronizadorCatalogos.class);

    private final CatalogoXubio fuente;
    private final CuentaContableRepository contableRepo;
    private final CircuitoContableRepository circuitoRepo;

    public SincronizadorCatalogos(CatalogoXubio fuente,
                                  CuentaContableRepository contableRepo,
                                  CircuitoContableRepository circuitoRepo) {
        this.fuente = fuente;
        this.contableRepo = contableRepo;
        this.circuitoRepo = circuitoRepo;
    }

    /** Que quedo de la ultima sincronizacion. Para el log y para las pruebas. */
    public record Resultado(int contablesNuevas, int circuitosNuevos,
                            int contablesActualizadas, int circuitosActualizados,
                            int filasSinId, boolean sincronizado) {

        public static Resultado nada() {
            return new Resultado(0, 0, 0, 0, 0, false);
        }
    }

    /**
     * Sincroniza los dos catalogos en una transaccion.
     *
     * Toda o nada: si el insert de los circuitos falla, no queda la mitad del
     * catalogo. Un filtro a medio sincronizar muestra cuentas nuevas junto a
     * contables viejas, y eso es peor que no sincronizar.
     */
    @Transactional
    public Resultado sincronizar() {
        if (!fuente.habilitado()) {
            log.debug("Catalogo de {} no habilitado: no se sincroniza nada.", fuente.nombre());
            return Resultado.nada();
        }

        Catalogos catalogos = fuente.consultar();
        if (catalogos.vacio()) {
            // Vacio y bien formado: Xubio contesto de verdad y no tiene nada. No es
            // un error, y sobre todo NO se borra lo que hay en la base: un fallo de
            // red que se tradujera en un catalogo vacio vaciaria la pantalla.
            log.warn("{} contesto sin catalogos. No se inserta ni se borra nada.", fuente.nombre());
            return new Resultado(0, 0, 0, 0, 0, true);
        }

        Conteo contables = contables(catalogos.cuentasContables());
        Conteo circuitos = circuitos(catalogos.circuitos());

        log.info("Catalogos de {} sincronizados: {} contables ({} nuevas, {} actualizadas), "
                        + "{} circuitos ({} nuevos, {} actualizados). Filas sin id de Xubio, "
                        + "descartadas: {}. Las cuentas bancarias NO se sincronizan: la API de "
                        + "Xubio no las expone.",
                fuente.nombre(),
                catalogos.cuentasContables().size(), contables.nuevas(), contables.actualizadas(),
                catalogos.circuitos().size(), circuitos.nuevas(), circuitos.actualizadas(),
                contables.descartadas() + circuitos.descartadas());

        return new Resultado(contables.nuevas(), circuitos.nuevas(),
                contables.actualizadas(), circuitos.actualizadas(),
                contables.descartadas() + circuitos.descartadas(), true);
    }

    /**
     * Que hizo un catalogo.
     *
     * Un record y no un `int[]` a proposito: con un array hay que acordarse de que
     * posicion es cada cosa, y el forget se paga con un log que dice numeros que
     * no corresponden con nada.
     */
    private record Conteo(int nuevas, int actualizadas, int descartadas) {
        static Conteo de(int nuevas, int actualizadas, int descartadas) {
            return new Conteo(nuevas, actualizadas, descartadas);
        }
    }

    /**
     * Si hay alguna fuente prendida.
     *
     * Lo consulta el arranque para decidir si el resultado de `sincronizar()` fue
     * "no havia nada que sincronizar" o "todo bien". Con Xubio apagado el resultado
     * es vacio y correcto, y la pantalla tiene que andar con lo que hay en la base
     * sin avisar nada.
     */
    public boolean sincronizado() {
        return fuente.habilitado();
    }

    /** @return nuevas, actualizadas y descartadas de este catalogo. */
    private Conteo contables(List<ItemCatalogo> items) {
        int nuevas = 0;
        int actualizadas = 0;
        int descartadas = 0;
        for (ItemCatalogo item : items) {
            if (!item.tieneId()) {
                descartadas++;
                continue;
            }
            // `codigo` es NOT NULL. Si Xubio no lo manda, se usa el id: es feo, pero
            // es unico y estable, y un codigo inventado seria peor.
            String codigo = vacioANull(item.codigo()) != null ? item.codigo() : item.id();
            String nombre = vacioANull(item.nombre()) != null ? item.nombre() : codigo;

            var existente = contableRepo.findByXubioId(item.id());
            if (existente.isPresent()) {
                existente.get().actualizarDesde(codigo, nombre);
                actualizadas++;
            } else {
                contableRepo.save(new CuentaContable(codigo, nombre, item.id()));
                nuevas++;
            }
        }
        return Conteo.de(nuevas, actualizadas, descartadas);
    }

    /** @return nuevas, actualizadas y descartadas de este catalogo. */
    private Conteo circuitos(List<ItemCatalogo> items) {
        int nuevas = 0;
        int actualizadas = 0;
        int descartadas = 0;
        for (ItemCatalogo item : items) {
            if (!item.tieneId()) {
                descartadas++;
                continue;
            }
            String nombre = vacioANull(item.nombre()) != null ? item.nombre() : "Circuito " + item.id();
            var existente = circuitoRepo.findByXubioId(item.id());
            if (existente.isPresent()) {
                existente.get().renombrar(nombre);
                actualizadas++;
            } else {
                circuitoRepo.save(new CircuitoContable(nombre, item.id()));
                nuevas++;
            }
        }
        return Conteo.de(nuevas, actualizadas, descartadas);
    }

    /** Un string vacio o con espacios es "no vino", y se trata como null. */
    private static String vacioANull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
