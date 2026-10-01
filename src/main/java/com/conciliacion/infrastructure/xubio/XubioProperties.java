package com.conciliacion.infrastructure.xubio;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
 * ── LAS RUTAS ESTAN CONFIRMADAS, NO SON UN SUPUESTO ───────────────────────────
 *
 * Antes de escribirlas aca, la spec de Xubio. Publica su OpenAPI en
 *
 *   https://xubio.com/API/1.1/swagger.json
 *
 * y de ahi salen, textualmente, los tres default de este record. Antes eran un
 * invento tomado de un ejemplo generico; ahora son los de la API real.
 *
 * ── POR QUE SIGUEN SIENDO CONFIGURABLES ───────────────────────────────────────
 *
 * Porque son configurables aunque ya se haya consultado la documentacion y el
 * default sea el valor correcto. Xubio tiene mas de un ambiente y un plan puede
 * apuntar a otro; con esto, se corrige en el yml y se reinicia, sin recompilar.
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
    private String rutaToken = "/TokenEndpoint";

    /**
     * Cuentas contables. OJO: se llama `cuenta` y no `cuentas-contables` porque asi
     * se llama en Xubio, y es el nombre corto: no lo que unopondria.
     */
    private String rutaCuentasContables = "/cuenta";

    /** Circuitos contables. */
    private String rutaCircuitos = "/circuitoContableBean";

    /**
     * La empresa a la que pertenece el par de credenciales.
     *
     * Es la unica ruta de la spec que devuelve la empresa en vez de un catalogo.
     * La usa `XubioEmpresas` para poner el nombre real en la barra, y es la razon
     * por la que el nombre de cada empresa no se escribe en la configuracion: si se
     * escribiera, la lista dejaria de coincidir con Xubio en silencio.
     */
    private String rutaMiEmpresa = "/miempresa";

    /**
     * Empresas adicionales, una App Cliente de Xubio cada una.
     *
     * ── POR QUE SON VARIAS Y NO UNA SOLA ───────────────────────────────────────
     *
     * Porque en la API publica de Xubio no existe el concepto de estudio ni de
     * "empresas de un usuario". Se recorrio la spec entera (63 rutas) y la palabra
     * "estudio" no aparece: todas las rutas usan `client_credentials`, y cada par
     * client-id / client-secret pertenece a una sola empresa. El login con usuario
     * y password, que si lista las empresas de un estudio, es de la app web de
     * Xubio y no esta documentado.
     *
     * La consecuencia practica: para operar la segunda empresa hay que crear una
     * segunda App Cliente en Xubio (Configuracion -> Integraciones -> API de Xubio),
     * y declararla aca. No hay forma de que la app las descubra sola.
     *
     * La empresa principal NO se declara aca: son las credenciales sueltas de este
     * mismo record, que ya usaban los catalogos. Sigue siendo la "default", y asi
     * agregar empresas no obliga a mover lo que ya andaba.
     */
    private List<EmpresaXubio> empresas = new ArrayList<>();

    /**
     * Una App Cliente de Xubio.
     *
     * `clave` es el identificador con el que la elige el usuario en la pantalla y
     * con el que se guarda la seleccion. No puede cambiar sin romper esa seleccion.
     * El nombre de la empresa NO va aca: lo trae `GET /miempresa`.
     */
    public static class EmpresaXubio {
        private String clave;
        private String clientId;
        private String clientSecret;

        public String getClave() { return clave; }
        public void setClave(String clave) { this.clave = clave; }

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }

        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    }

    /**
     * NO hay ruta de cuentas bancarias, y no es una omision: la API de Xubio no
     * expone las cuentas bancarias de la empresa. Se recorrio la spec entera y el
     * unico recurso con "banco" en el nombre es `GET /banco`, que devuelve el
     * catalogo de entidades bancarias (Nacion, Galicia, Santander), no las cuentas
     * de la empresa, y sin CBU ni numero de cuenta.
     *
     * El CBU es justamente contra lo que se concilia, asi que el filtro de cuenta
     * bancaria se queda como dato local. Ver `DataSeeder` y `CuentaBancaria`.
     */

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

    public String getRutaCuentasContables() { return rutaCuentasContables; }
    public void setRutaCuentasContables(String v) { this.rutaCuentasContables = v; }

    public String getRutaCircuitos() { return rutaCircuitos; }
    public void setRutaCircuitos(String v) { this.rutaCircuitos = v; }

    public String getRutaMiEmpresa() { return rutaMiEmpresa; }
    public void setRutaMiEmpresa(String v) { this.rutaMiEmpresa = v; }

    public List<EmpresaXubio> getEmpresas() { return empresas; }
    public void setEmpresas(List<EmpresaXubio> empresas) { this.empresas = empresas; }

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }

    public Duration getMargenRenovacion() { return margenRenovacion; }
    public void setMargenRenovacion(Duration v) { this.margenRenovacion = v; }
}
