package com.conciliacion.infrastructure.xubio;

import com.conciliacion.application.empresa.Empresa;
import com.conciliacion.application.empresa.EmpresasXubio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Arma la lista de empresas, preguntandole a cada una su nombre a Xubio.
 *
 * ── POR QUE EL NOMBRE VIENE DE LA API Y NO DE LA CONFIGURACION ───────────────
 *
 * `GET /miempresa` es la unica fuente de verdad sobre como se llama la empresa. Si
 * el nombre se escribiera en el yml, la lista dejaria de coincidir con Xubio en
 * silencio: nadie se entera hasta que alguien se equivoca de empresa al conciliar.
 *
 * Y el nombre se pide en cada lectura, no una vez al arrancar, porque es una
 * llamada barata y la app es monousuario de escritorio. Lo que SI se cachea es el
 * token, que dura una hora: N empresas cada vez que se abre la barra son N tokens
 * si no.
 *
 * ── UNA EMPRESA SIN ACCESO NO SE SACA DE LA LISTA ────────────────────────────
 *
 * Se devuelve con `estado = SIN_ACCESO` y el motivo. Filtrarla haria que una
 * credencial vencida se viera como una empresa borrada, y el usuario iria a
 * revisar la configuracion de Xubio en vez de la app.
 *
 * ── POR QUE NO SE PROPAGA UNA EXCEPCION ──────────────────────────────────────
 *
 * El contrato de este metodo no es "todo o nada". Una sola empresa con el secreto
 * mal pegado no puede impedir que las otras se puedan elegir, y no es un error que
 * `/filtros/opciones` no pueda mostrar: el 503 de ahi es para "no se pudo leer NADA".
 */
@Component
public class XubioEmpresas implements EmpresasXubio {

    private static final Logger log = LoggerFactory.getLogger(XubioEmpresas.class);

    /**
     * La empresa "principal": las credenciales sueltas del yml. Se la llama asi
     * porque es la que usan los catalogos, y asi esa empresa sigue funcionando sin
     * tocar la configuracion. Las de `empresas[]` son adicionales.
     */
    static final String CLAVE_PRINCIPAL = "default";

    private final XubioProperties props;
    private final RestClient http;

    /** Token por empresa. La clave es la clave de configuracion de la empresa. */
    private final Map<String, TokenVigente> tokens = new ConcurrentHashMap<>();

    public XubioEmpresas(XubioProperties props, RestClient xubioHttp) {
        this.props = props;
        this.http = xubioHttp;
    }

    private record TokenVigente(String cabecera, Instant vence) {
        boolean vigente(Instant ahora) {
            return ahora.isBefore(vence);
        }
    }

    @Override
    public List<Empresa> consultar() {
        if (!props.isHabilitado()) {
            // Fuente apagada a proposito. No es un error: la pantalla lo muestra como
            // "ninguna empresa configurada", que es exactamente lo que es.
            return List.of();
        }

        List<Empresa> empresas = new ArrayList<>();
        Set<String> clavesVistas = new HashSet<>();

        if (props.tieneCredenciales()) {
            empresas.add(una(CLAVE_PRINCIPAL, props.getClientId(), props.getClientSecret()));
            clavesVistas.add(CLAVE_PRINCIPAL);
        }

        for (XubioProperties.EmpresaXubio declarada : props.getEmpresas()) {
            String clave = declarada.getClave();
            if (clave == null || clave.isBlank()) {
                // No se le inventa una clave: un entry sin clave es un error de
                // configuracion, y taparlo con una clave sintetica esconde justo eso.
                log.warn("Hay una entrada en conciliacion.xubio.empresas sin 'clave'; se ignora.");
                continue;
            }
            if (!clavesVistas.add(clave)) {
                // La `clave` es el valor del desplegable. Dos entradas con la misma
                // hacen que al elegir una se muestren las dos.
                log.warn("La empresa '{}' esta declarada mas de una vez; se ignora la repetida.", clave);
                continue;
            }
            empresas.add(una(clave, declarada.getClientId(), declarada.getClientSecret()));
        }
        return List.copyOf(empresas);
    }

    /** Una empresa: su nombre real, o el motivo por el que no se pudo leer. */
    private Empresa una(String clave, String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return Empresa.sinAcceso(clave,
                    "Faltan las credenciales de esta empresa (client-id o client-secret).");
        }
        try {
            String nombre = nombreEnXubio(clave, clientId, clientSecret);
            // Xubio contesto bien pero sin nombre: las credenciales sirven, asi que la
            // empresa entra. Se muestra la clave, que es lo unico que hay para
            // distinguirla, y no se la marca como caida.
            return new Empresa(clave, nombre == null || nombre.isBlank() ? clave : nombre,
                    Empresa.OK, null);
        } catch (FalloDeEmpresa e) {
            return Empresa.sinAcceso(clave, e.getMessage());
        }
    }

    /** El `nombreEmpresa` de `GET /miempresa`, o el motivo por el que no se pudo. */
    private String nombreEnXubio(String clave, String clientId, String clientSecret) {
        MiEmpresaXubio empresa = leer(clave, clientId, clientSecret);
        return empresa == null ? null : empresa.getNombreEmpresa();
    }

    private MiEmpresaXubio leer(String clave, String clientId, String clientSecret) {
        try {
            return pedir(clave, clientId, clientSecret, false);
        } catch (FalloDeEmpresa e) {
            if (!"401".equals(e.getCodigo())) {
                throw e;
            }
            // Un 401 con token en vigor lo mas probable es que vencio entre el chequeo
            // y el uso. Se borra la cache de esa empresa y se reintenta una sola vez.
            tokens.remove(clave);
            return pedir(clave, clientId, clientSecret, true);
        }
    }

    /**
     * @param reintentando marca la segunda vuelta, para que un 401 seguido no entre
     *                     en un bucle. El fallo se lanza igual, asi que no hay
     *                     recursion posible.
     */
    private MiEmpresaXubio pedir(String clave, String clientId, String clientSecret, boolean reintentando) {
        String autorizacion = cabecera(clave, clientId, clientSecret);
        try {
            MiEmpresaXubio empresa = http.get()
                    .uri(props.getRutaMiEmpresa())
                    .header("Authorization", autorizacion)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(MiEmpresaXubio.class);
            if (empresa == null) {
                throw new FalloDeEmpresa("Xubio devolvio una respuesta vacia para '" + clave + "'.", "200");
            }
            return empresa;
        } catch (RestClientResponseException e) {
            HttpStatusCode estado = e.getStatusCode();
            throw new FalloDeEmpresa(
                    "Xubio devolvio " + estado.value() + " al leer /miempresa de '" + clave + "'. "
                            + XubioCredenciales.cuerpoDeError(e),
                    String.valueOf(estado.value()));
        } catch (ResourceAccessException e) {
            String causa = causa(e);
            boolean timeout = causa.toLowerCase().contains("timed out")
                    || causa.toLowerCase().contains("timeout");
            throw new FalloDeEmpresa(timeout
                    ? "Xubio no respondio a tiempo (" + props.getTimeout().getSeconds() + " s)."
                    : "No se pudo conectar con Xubio: " + causa,
                    "timeout");
        } catch (RestClientException e) {
            throw new FalloDeEmpresa("No se pudo leer /miempresa de '" + clave + "': " + causa(e), "error");
        }
    }

    /** El header Authorization de una empresa, con su token cacheado. */
    private String cabecera(String clave, String clientId, String clientSecret) {
        Instant ahora = Instant.now();
        TokenVigente vigente = tokens.get(clave);
        if (vigente != null && vigente.vigente(ahora)) {
            return vigente.cabecera();
        }

        // El cuerpo lleva SOLO el grant_type. Las credenciales van por HTTP Basic.
        MultiValueMap<String, String> cuerpo = new LinkedMultiValueMap<>();
        cuerpo.add("grant_type", "client_credentials");

        RespuestaToken respuesta;
        try {
            respuesta = http.post()
                    .uri(props.getRutaToken())
                    .header("Authorization", XubioCredenciales.basic(clientId, clientSecret))
                    .body(cuerpo)
                    .retrieve()
                    .body(RespuestaToken.class);
        } catch (RestClientResponseException e) {
            HttpStatusCode estado = e.getStatusCode();
            throw new FalloDeEmpresa(
                    "Xubio rechazo el token de '" + clave + "' (" + estado.value() + "). "
                            + XubioCredenciales.cuerpoDeError(e),
                    String.valueOf(estado.value()));
        } catch (ResourceAccessException e) {
            throw new FalloDeEmpresa("No se pudo pedir el token de '" + clave + "': " + causa(e), "timeout");
        } catch (RestClientException e) {
            throw new FalloDeEmpresa("No se pudo pedir el token de '" + clave + "': " + causa(e), "error");
        }

        if (respuesta == null || respuesta.getAccessToken() == null
                || respuesta.getAccessToken().isBlank()) {
            throw new FalloDeEmpresa(
                    "Xubio contesto sin token para '" + clave + "'. Revisar su client-id y client-secret.",
                    "200");
        }

        Integer segundos = respuesta.segundosDeVida();
        int vida = segundos != null && segundos > 0 ? segundos : 1800;
        String tipo = respuesta.getTokenType();
        String cabecera = (tipo == null || tipo.isBlank() ? "Bearer" : tipo) + " "
                + respuesta.getAccessToken();
        tokens.put(clave, new TokenVigente(cabecera,
                ahora.plusSeconds(vida).minus(props.getMargenRenovacion())));
        return cabecera;
    }

    private static String causa(Throwable e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    /**
     * Fallo de UNA empresa. Interna: no sale de `XubioEmpresas`, se convierte en un
     * `Empresa` con estado SIN_ACCESO.
     */
    private static class FalloDeEmpresa extends RuntimeException {
        private final String codigo;

        FalloDeEmpresa(String mensaje, String codigo) {
            super(mensaje);
            this.codigo = codigo;
        }

        String getCodigo() {
            return codigo;
        }
    }
}
