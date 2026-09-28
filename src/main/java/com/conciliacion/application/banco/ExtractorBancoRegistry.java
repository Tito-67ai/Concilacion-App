package com.conciliacion.application.banco;

import com.conciliacion.infrastructure.banco.BancoConfiguracion;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resuelve codigo de extractor -> implementacion. Spring inyecta todos los
 * ExtractorBancario que haya en el classpath, asi que agregar un banco es agregar
 * una clase con @Component y nada mas: aparece solo en el mapa y en la UI.
 *
 * Aca NO se inventan extractores ni se valida que el banco exista. Si se pide uno
 * que no esta, hay que fallar, no devolver uno vacio que hace que el usuario piense
 * que importo bien.
 */
@Component
public class ExtractorBancoRegistry {

    private final Map<String, ExtractorBancario> porCodigo = new LinkedHashMap<>();

    public ExtractorBancoRegistry(List<ExtractorBancario> extractores, BancoConfiguracion config) {
        for (ExtractorBancario e : extractores) {
            porCodigo.put(e.codigo(), e);
        }
        porCodigo.values().removeIf(e -> !config.habilitado(e.codigo()));
    }

    public List<ExtractorBancario> disponibles() {
        return List.copyOf(porCodigo.values());
    }

    public boolean existe(String codigo) {
        return porCodigo.containsKey(codigo);
    }

    /** Lanza si el codigo no existe o esta deshabilitado. */
    public ExtractorBancario obtener(String codigo) {
        ExtractorBancario e = porCodigo.get(codigo);
        if (e == null) {
            throw new ExtractorDesconocidoException(codigo, List.copyOf(porCodigo.keySet()));
        }
        return e;
    }
}
