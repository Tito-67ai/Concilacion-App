package com.conciliacion.infrastructure.banco;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuracion por fuente, bajo `conciliacion.bancos.<CODIGO>`.
 *
 * Existe para que la forma de conectar una API este definida ANTES de que haya una
 * API que conectar. Cuando llegue el banco nuevo, el trabajo es agregar el bloque en
 * application.yml, escribir el extractor y registrar las credenciales como variables
 * de entorno: nada de esto cambia.
 *
 * ── DONDE VAN LAS CREDENCIALES ────────────────────────────────────────────────
 * NO van aca. Este bloque es para datos de estructura (nombre, si esta prendido, a
 * que URL pegarle). El token va como variable de entorno:
 *
 *   GALICIA_API_TOKEN=${GALICIA_API_TOKEN:}
 *
 * con el valor real exportado en la maquina o en el secret manager del deploy. Si
 * queda escrito en application.yml, el proximo `git push` lo sube a GitHub y ahi
 * queda para siempre.
 *
 * La clase no tiene ningun campo `token`: es deliberado. Para agregar uno habria que
 * cambiar esta clase, que es la senal de que hace falta decidir como se cifra.
 */
@ConfigurationProperties(prefix = "conciliacion.bancos")
public class BancoConfiguracion {

    private Map<String, Definicion> porCodigo = new HashMap<>();

    public Map<String, Definicion> getPorCodigo() { return porCodigo; }
    public void setPorCodigo(Map<String, Definicion> porCodigo) { this.porCodigo = porCodigo; }

    public boolean habilitado(String codigo) {
        Definicion d = porCodigo.get(codigo);
        return d == null || d.isHabilitado();
    }

    public static class Definicion {
        private String nombre;
        private boolean habilitado = true;
        private String urlBase;

        public String getNombre() { return nombre; }
        public void setNombre(String nombre) { this.nombre = nombre; }
        public boolean isHabilitado() { return habilitado; }
        public void setHabilitado(boolean habilitado) { this.habilitado = habilitado; }
        public String getUrlBase() { return urlBase; }
        public void setUrlBase(String urlBase) { this.urlBase = urlBase; }
    }
}
