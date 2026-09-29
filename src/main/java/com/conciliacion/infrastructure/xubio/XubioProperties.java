package com.conciliacion.infrastructure.xubio;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Como se habla con la API de Xubio.
 *
 * ── DONDE ESTAN LAS CREDENCIALES ──────────────────────────────────────────────
 *
 * NO estan en este record, y es a proposito. Todo lo que se puede escribir en el
 * repo llega a GitHub: esta clase es Java plano, no tiene ningun secreto, y el
 * client-id / client-secret se leen del entorno:
 *
 *   XUBIO_CLIENT_ID=...
 *   XUBIO_CLIENT_SECRET=...
 *
 * que en application.yml estan referenciados asi:
 *
 *   conciliacion:
 *     xubio:
 *       client-id: ${XUBIO_CLIENT_ID:}
 *       client-secret: ${XUBIO_CLIENT_SECRET:}
 *
 * Si el valor por defecto fuera el secreto, el `git push` lo sube y despues hay
 * que revocarlo. Un default vacio hace que el error sea "no hay credenciales" en
 * vez de "el repo esta filtrado".
 *
 * ── POR QUE LAS RUTAS SON CONFIGURABLES Y NO CONSTANTES ───────────────────────
 *
 * El codigo del snippet que motiva esto tiene las rutas "/cuentas" y "/circuitos"
 * escritas, y el mismo comentario dice "revisa en la documentacion la URL exacta".
 * O sea: no las sabia. Si quedan como constante, el dia que se confirmen hay que
 * recompilar; como configuracion, se corrigen en el yml y se reinicia.
 */
@ConfigurationProperties(prefix = "conciliacion.xubio")
public class XubioProperties {

    /**
     * Si esta fuente esta prendida. En false no se intenta ni el token ni los
     * catalogos, y el filtro se arma con lo que haya en la base.
     */
    private boolean habilitado = false;

    /** Base de la API. Sin barra final. */
    private String baseUrl = "";

    private String clientId = "";
    private String clientSecret = "";

    /** Ruta del token, relativa a baseUrl. */
    private String rutaToken = "/auth/token";

    private String rutaCuentasBancarias = "/cuentas-bancarias";
    private String rutaCuentasContables = "/cuentas-contables";
    private String rutaCircuitos = "/circuitos";

    /**
     * Corte de conexion y de lectura.
     *
     * Sin esto, un Xubio que acepta la conexion y despues se cuelga deja el GET
     * esperando para siempre, y con el la barra de filtros de la pantalla. El
     * usuario ve la pagina cargando y no tiene forma de saber si falta la red, si
     * Xubio esta caido o si la app se rompio. Cortar y decir "no respondio" es
     * mucho mejor que esperar.
     */
    private Duration timeout = Duration.ofSeconds(10);

    /**
     * Si el token se renueva antes de que expire, por margen.
     *
     * El margen evita el caso molesto: el token vence justo entre el chequeo y el
     * uso, y la llamada falla con 401 aunque el token "estaba bien". Renovando
     * antes de la expiracion ese caso no existe.
     */
    private Duration margenRenovacion = Duration.ofMinutes(1);

    /**
     * Si hay credenciales para intentar la llamada. No es lo mismo que
     * `habilitado`: se puede tener la fuente prendida y no tener todavia el
     * token, que es el estado normal de una maquina de desarrollo recien
     * arrancada, y en ese estado la fuente ESTA prendida y hay que avisar que
     * falta el token.
     */
    public boolean tieneCredenciales() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    public boolean isHabilitado() { return habilitado; }
    public void setHabilitado(boolean habilitado) { this.habilitado = habilitado; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public String getRutaToken() { return rutaToken; }
    public void setRutaToken(String rutaToken) { this.rutaToken = rutaToken; }

    public String getRutaCuentasBancarias() { return rutaCuentasBancarias; }
    public void setRutaCuentasBancarias(String v) { this.rutaCuentasBancarias = v; }

    public String getRutaCuentasContables() { return rutaCuentasContables; }
    public void setRutaCuentasContables(String v) { this.rutaCuentasContables = v; }

    public String getRutaCircuitos() { return rutaCircuitos; }
    public void setRutaCircuitos(String rutaCircuitos) { this.rutaCircuitos = rutaCircuitos; }

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }

    public Duration getMargenRenovacion() { return margenRenovacion; }
    public void setMargenRenovacion(Duration v) { this.margenRenovacion = v; }
}
