package com.conciliacion.infrastructure.xubio;

import com.conciliacion.application.catalogo.CatalogoNoDisponibleException;
import com.conciliacion.application.catalogo.CatalogoXubio;
import com.conciliacion.application.catalogo.Catalogos;
import com.conciliacion.application.catalogo.ItemCatalogo;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Habla con la API de Xubio: pide un token y despues los dos catalogos.
 *
 * ── EL PROTOCOLO DE TOKEN, QUE NO ES EL QUE SE SUPIA ─────────────────────────
 *
 * Xubio documenta el pedido asi:
 *
 *   curl -X POST https://xubio.com/API/1.1/TokenEndpoint \
 *        -H "Content-Type: application/x-www-form-urlencoded" \
 *        -d 'grant_type=client_credentials' \
 *        --user TU_CLIENT_ID:TU_SECRET_ID
 *
 * Tres cosas de ahi que hay que cumplir exactamente:
 *
 *  1. La ruta es `/TokenEndpoint`, no `/auth/token` ni `/token`.
 *  2. El cuerpo NO lleva las credenciales: lleva `grant_type=client_credentials`.
 *     Las credenciales van por HTTP Basic, en la cabecera `Authorization`.
 *  3. El cuerpo va como `application/x-www-form-urlencoded`, no como JSON.
 *
 * Mandar un JSON con `clientId`/`clientSecret` no falla de forma ruidosa: Xubio
 * contesta `400 invalid_client`, que se lee como "las credenciales estan mal"
 * cuando en realidad estan bien y se mandaron donde no era.
 *
 * ── QUE CONTESTA CUANDO EL TOKEN NO ES ────────────────────────────────────────
 *
 * Un 401 sin token, o con token vencido, responde:
 *
 *   {"status": "error","message": "UNAUTHORIZED_ACCESS"}
 *
 * y un 400 por credenciales invalidas responde:
 *
 *   {"error_description":"Client authentication failed","error":"invalid_client"}
 *
 * Son dos formas distintas y se traducen distinto: el 400 no se reintenta nunca
 * (con las mismas credenciales devuelve lo mismo), y el 401 de un catalogo si, una
 * sola vez, porque lo mas probable es un token que vencio entre el chequeo y el uso.
 *
 * ── POR QUE EL TOKEN SE CACHEA ────────────────────────────────────────────────
 *
 * Pedir un token por cada lectura de catalogo es una llamada de mas por request y
 * una vida extra por token. Xubio lo da por una hora (`expires_in: "3600"`), asi
 * que la cache se invalida sola por tiempo, con un margen antes de la expiracion,
 * y tambien cuando Xubio contesta 401.
 */
@Component
public class XubioCliente implements CatalogoXubio {

    private static final Logger log = LoggerFactory.getLogger(XubioCliente.class);

    private final XubioProperties props;
    private final RestClient http;

    /** Token en vigor y hasta cuando. AtomicReference y no un campo pelado. */
    private final AtomicReference<TokenVigente> token = new AtomicReference<>();

    public XubioCliente(XubioProperties props, RestClient xubioHttp) {
        this.props = props;
        this.http = xubioHttp;
    }

    private record TokenVigente(String valor, String tipo, Instant vence) {
        boolean vigente(Instant ahora) {
            return ahora.isBefore(vence);
        }
    }

    @Override
    public boolean habilitado() {
        // SOLO el interruptor, y a proposito. Antes esto devolvia
        // `props.estaConfigurado()`, que ademas exige credenciales, y eso
        // fundia dos estados que son distintos:
        //
        //   - fuente prendida y sin credenciales (una maquina de desarrollo nueva)
        //   - fuente apagada (nadie quiere Xubio)
        //
        // Con los dos fundidos, la app con Xubio prendido y sin credenciales se
        // comportaba como si estuviera apagada: sembraba sus cuentas inventadas y
        // no decaia ni una vez. Justo el fallo que el resto del diseno trata de
        // evitar. Prendido es prendido; si faltan las credenciales, lo dice
        // `consultar()`, con un mensaje que las nombra.
        return props.isHabilitado();
    }

    @Override
    public String nombre() {
        return "Xubio";
    }

    @Override
    public Catalogos consultar() {
        if (!props.isHabilitado()) {
            // No es un error: la fuente esta apagada a proposito. Se devuelve un
            // catalogo vacio y el filtro se arma con lo que haya en la base.
            return new Catalogos(List.of(), List.of());
        }
        if (!props.tieneCredenciales()) {
            throw new CatalogoNoDisponibleException(
                    "Xubio esta habilitado pero faltan las credenciales. "
                            + "Faltan client-id o client-secret (variables XUBIO_CLIENT_ID y XUBIO_CLIENT_SECRET).",
                    false);
        }
        if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) {
            throw new CatalogoNoDisponibleException(
                    "Xubio esta habilitado pero no tiene base-url configurada.", false);
        }

        String autorizacion = tokenValido();
        try {
            return dosCatalogos(autorizacion);
        } catch (CatalogoNoDisponibleException e) {
            // Un 401 con token en vigor: lo mas probable es que vencio entre el
            // chequeo y el uso. Se borra la cache y se reintenta una sola vez.
            if (e.getMessage() != null && e.getMessage().contains("401") && token.get() != null) {
                log.warn("Xubio devolvio 401 con un token en cache; se renueva y se reintenta una vez.");
                token.set(null);
                return dosCatalogos(tokenValido());
            }
            throw e;
        }
    }

    private Catalogos dosCatalogos(String autorizacion) {
        return new Catalogos(
                leer(props.getRutaCuentasContables(), autorizacion, "cuentas contables"),
                leer(props.getRutaCircuitos(), autorizacion, "circuitos"));
    }

    /** Devuelve el token, pidiendolo solo si no hay uno vigente. */
    private String tokenValido() {
        Instant ahora = Instant.now();
        TokenVigente actual = token.get();
        if (actual != null && actual.vigente(ahora)) {
            return cabecera(actual.valor(), actual.tipo());
        }
        return pedirToken();
    }

    /**
     * El valor completo del header Authorization: "Bearer tok-123".
     *
     * Devolver solo el token sin el prefijo es el error clasico de esto, y es
     * silencioso: Xubio contesta 401, el mensaje dice "credenciales", y el
     * client-id y el secret estan impecable. Se devuelve el header entero y no
     * el token para que el prefijo no se pueda olvidar en ningun camino.
     *
     * El tipo sale de la respuesta (`token_type`) y no se fuerza "Bearer", porque
     * si Xubio dice otra cosa, es Xubio la que sabe y no este codigo.
     */
    private static String cabecera(String token, String tipo) {
        return (tipo == null || tipo.isBlank() ? "Bearer" : tipo) + " " + token;
    }

    private String pedirToken() {
        // El cuerpo lleva SOLO el grant_type. Las credenciales van por HTTP Basic.
        MultiValueMap<String, String> cuerpo = new LinkedMultiValueMap<>();
        cuerpo.add("grant_type", "client_credentials");

        RespuestaToken respuesta;
        try {
            respuesta = http.post()
                    .uri(props.getRutaToken())
                    .header("Authorization", basic(props.getClientId(), props.getClientSecret()))
                    .body(cuerpo)
                    .retrieve()
                    .body(RespuestaToken.class);
        } catch (RestClientResponseException e) {
            throw errorDeToken(e);
        } catch (ResourceAccessException e) {
            throw sinRespuesta(e);
        } catch (RestClientException e) {
            throw new CatalogoNoDisponibleException(
                    "No se pudo pedir el token a Xubio: " + causa(e), true, e);
        }

        if (respuesta == null || respuesta.getAccessToken() == null
                || respuesta.getAccessToken().isBlank()) {
            // Xubio contesto 200 sin token. Casi siempre es que las credenciales
            // estan mal y la API responde con un cuerpo de error en vez de un 401.
            throw new CatalogoNoDisponibleException(
                    "Xubio contesto sin token. Revisar el client-id y el client-secret.", false);
        }

        // Xubio manda `expires_in` como texto ("3600"). Si no dice cuando vence, se
        // renueva cada 30 minutos: es un supuesto marcado, y con un token de vida
        // corta renovar antes de que caduque es inofensivo, con uno largo se hacen
        // llamadas de mas.
        Integer segundos = respuesta.segundosDeVida();
        int vida = segundos != null && segundos > 0 ? segundos : 1800;
        Instant vence = Instant.now().plusSeconds(vida).minus(props.getMargenRenovacion());

        token.set(new TokenVigente(respuesta.getAccessToken(),
                respuesta.getTokenType(), vence));
        log.info("Token de Xubio obtenido; se renueva en {} segundos.", Math.max(0, vida));
        return cabecera(respuesta.getAccessToken(), token.get().tipo());
    }

    /**
     * HTTP Basic con client-id y client-secret.
     *
     * Base64 de "id:secreto". Es lo que dice la documentacion de Xubio y lo que
     * hace `--user` de curl. Un secreto con dos puntos se parte mal a mano, por eso
     * se hace con `lastIndexOf`: el id puede tenerlos y no son separadores.
     */
    private static String basic(String id, String secreto) {
        StringBuilder credenciales = new StringBuilder(id);
        int ultimo = secreto.lastIndexOf(':');
        if (ultimo >= 0) {
            credenciales.append(secreto.substring(ultimo));
        } else {
            credenciales.append(':').append(secreto);
        }
        return "Basic " + Base64.getEncoder()
                .encodeToString(credenciales.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Un catalogo. El nombre va en el mensaje de error porque "no se pudieron leer
     * los catalogos" no dice cual de los dos fallo, y el usuario tiene dos llamadas
     * distintas que puede estar viendo.
     */
    private List<ItemCatalogo> leer(String ruta, String autorizacion, String nombre) {
        RespuestaItem[] crudos;
        try {
            crudos = http.get()
                    .uri(ruta)
                    .header("Authorization", autorizacion)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(RespuestaItem[].class);
        } catch (RestClientResponseException e) {
            throw errorDeLectura(e, nombre);
        } catch (ResourceAccessException e) {
            throw sinRespuesta(e);
        } catch (RestClientException e) {
            throw new CatalogoNoDisponibleException(
                    "No se pudieron leer los " + nombre + " de Xubio: " + causa(e), true, e);
        }

        if (crudos == null) {
            return List.of();
        }
        return Arrays.stream(crudos)
                .map(i -> new ItemCatalogo(i.getId(), i.getNombre(), i.getCodigo()))
                .toList();
    }

    /**
     * Un rechazo al pedir el token.
     *
     * Xubio contesta `400 invalid_client` cuando el client-id o el secret estan
     * mal, no 401. No se reintenta nunca: con las mismas credenciales devuelve
     * exactamente lo mismo, y un boton de "reintentar" que no puede funcionar es
     * peor que no tenerlo.
     */
    private CatalogoNoDisponibleException errorDeToken(RestClientResponseException e) {
        HttpStatusCode estado = e.getStatusCode();
        boolean reintentable = estado.is5xxServerError();
        return new CatalogoNoDisponibleException(
                "Xubio rechazo el pedido de token (" + estado.value() + "). "
                        + cuerpoDeError(e), reintentable, e);
    }

    private CatalogoNoDisponibleException errorDeLectura(RestClientResponseException e,
                                                          String nombre) {
        HttpStatusCode estado = e.getStatusCode();
        boolean reintentable = estado.is5xxServerError() || estado.value() == 429;
        return new CatalogoNoDisponibleException(
                "Xubio devolvio " + estado.value() + " al leer los " + nombre + ". "
                        + cuerpoDeError(e), reintentable, e);
    }

    private CatalogoNoDisponibleException sinRespuesta(ResourceAccessException e) {
        boolean timeout = causa(e).toLowerCase().contains("timed out")
                || causa(e).toLowerCase().contains("timeout");
        return new CatalogoNoDisponibleException(
                timeout
                        ? "Xubio no respondio a tiempo (" + props.getTimeout().getSeconds()
                                + " s). Puede ser un problema de red o que la API este caida."
                        : "No se pudo conectar con Xubio: " + causa(e),
                true, e);
    }

    /**
     * El cuerpo del error, que es donde la API dice que esta mal.
     *
     * Se recorta a 300 caracteres: un error de proxy puede devolver una pagina
     * HTML entera, y eso en un mensaje de la pantalla es ruido.
     */
    private String cuerpoDeError(RestClientResponseException e) {
        String cuerpo = e.getResponseBodyAsString();
        if (cuerpo == null || cuerpo.isBlank()) {
            return "sin detalle.";
        }
        String recortado = cuerpo.length() > 300 ? cuerpo.substring(0, 300) + "..." : cuerpo;
        return recortado.replaceAll("\\s+", " ").trim();
    }

    private static String causa(Throwable e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
