package com.conciliacion.infrastructure.xubio;

import com.conciliacion.application.catalogo.CatalogoNoDisponibleException;
import com.conciliacion.application.catalogo.CatalogoXubio;
import com.conciliacion.application.catalogo.Catalogos;
import com.conciliacion.application.catalogo.ItemCatalogo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Habla con la API de Xubio: pide un token y despues los tres catalogos.
 *
 * ── LO QUE ESTA PEOR QUE EN EL SNIPPET, Y POR QUE ─────────────────────────────
 *
 *  1. `new RestTemplate()` sin timeouts. Si Xubio acepta la conexion y se cuelga,
 *     el GET queda esperando para siempre y con el la pantalla de conciliacion.
 *     Aca el cliente es un bean con timeout de conexion y de lectura, y el
 *     timeout se traduce a un error que la pantalla puede mostrar.
 *
 *  2. `obtenerToken()` que devuelve "TOKEN_EXTRAIDO_DEL_JSON". Aca el token se
 *     deserializa de verdad, con tres nombres de campo aceptados, y se cachea
 *     hasta un margen antes de que expire.
 *
 *  3. `BASE_URL` constante "https://xubio.com/API/1.1". Aca es configuracion, y
 *     las tres rutas tambien, porque el propio snippet dice que hay que
 *     confirmarlas en la documentacion.
 *
 * ── POR QUE EL TOKEN SE CACHEA ────────────────────────────────────────────────
 *
 * Pedir un token por cada lectura de catalogo es una llamada de mas por request
 * y una vida extra por token. El filtro se pide una vez al entrar, pero si
 * aparece un "refrescar catalogos" manual, el token ya esta y no se vuelve a
 * pedir. La cache se invalida sola por tiempo, y tambien cuando Xubio contesta
 * 401: se borra y se reintenta UNA vez, porque un 401 en la primera lectura casi
 * siempre es un token que vencio entre el chequeo y el uso, no credenciales
 * malas. Un 401 en el segundo intento ya es de verdad un 401 de credenciales.
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
            return new Catalogos(List.of(), List.of(), List.of());
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
            return new Catalogos(
                    leer(props.getRutaCuentasBancarias(), autorizacion, "cuentas bancarias"),
                    leer(props.getRutaCuentasContables(), autorizacion, "cuentas contables"),
                    leer(props.getRutaCircuitos(), autorizacion, "circuitos"));
        } catch (CatalogoNoDisponibleException e) {
            // Un 401 con token en vigor: lo mas probable es que vencio entre el
            // chequeo y el uso. Se borra la cache y se reintenta una sola vez.
            if (e.getMessage() != null && e.getMessage().contains("401") && token.get() != null) {
                log.warn("Xubio devolvio 401 con un token en cache; se renueva y se reintenta una vez.");
                token.set(null);
                String nuevo = tokenValido();
                return new Catalogos(
                        leer(props.getRutaCuentasBancarias(), nuevo, "cuentas bancarias"),
                        leer(props.getRutaCuentasContables(), nuevo, "cuentas contables"),
                        leer(props.getRutaCircuitos(), nuevo, "circuitos"));
            }
            throw e;
        }
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
        String cuerpo = "{\"clientId\":%s,\"clientSecret\":%s}".formatted(
                json(props.getClientId()), json(props.getClientSecret()));

        RespuestaToken respuesta;
        try {
            respuesta = http.post()
                    .uri(props.getRutaToken())
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

        // Si Xubio no dice cuando vence, se renueva a los 30 minutos. Es un supuesto
        // marcado: con un token de vida corta, renovar antes de que caduque es
        // inofensivo; con uno largo, se hacen llamadas de mas.
        int segundos = respuesta.getExpiresIn() != null && respuesta.getExpiresIn() > 0
                ? respuesta.getExpiresIn()
                : 1800;
        Instant vence = Instant.now().plusSeconds(segundos).minus(props.getMargenRenovacion());

        token.set(new TokenVigente(respuesta.getAccessToken(),
                respuesta.getTokenType() == null ? "Bearer" : respuesta.getTokenType(), vence));
        log.info("Token de Xubio obtenido; se renueva en {} segundos.", Math.max(0, segundos));
        return cabecera(respuesta.getAccessToken(), token.get().tipo());
    }

    /**
     * Un catalogo. El nombre va en el mensaje de error porque "no se pudieron leer
     * los catalogos" no dice cual de los tres fallo, y el usuario tiene tres
     * llamadas distintas que puede estar viendo.
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
                .map(i -> new ItemCatalogo(i.getId(), i.getNombre(), i.getCodigo(), i.getGrupo()))
                .toList();
    }

    private CatalogoNoDisponibleException errorDeToken(RestClientResponseException e) {
        HttpStatusCode estado = e.getStatusCode();
        // 400 y 401 no son reintentables: el body dice por que, y repetirlo con las
        // mismas credenciales devuelve exactamente lo mismo.
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

    /** El cuerpo del error, que es donde la API dice que esta mal. */
    private String cuerpoDeError(RestClientResponseException e) {
        String cuerpo = e.getResponseBodyAsString();
        if (cuerpo == null || cuerpo.isBlank()) {
            return "sin detalle.";
        }
        // Se recortan los ultimos 300 caracteres: un error de proxy puede devolver
        // una pagina HTML entera, y eso en un mensaje de la pantalla es ruido.
        String recortado = cuerpo.length() > 300 ? cuerpo.substring(0, 300) + "..." : cuerpo;
        return recortado.replaceAll("\\s+", " ").trim();
    }

    private static String causa(Throwable e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    /** Comillas y escapes, para que un secreto con comillas no rompa el JSON. */
    private static String json(String valor) {
        if (valor == null) {
            return "\"\"";
        }
        return "\"" + valor.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
