package com.conciliacion.infrastructure.xubio;

import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * El pedacito del protocolo de Xubio que es el mismo en todos lados.
 *
 * ── POR QUE EXISTE ESTA CLASE ────────────────────────────────────────────────
 *
 * Porque el pedido de token tiene tres detalles que hay que cumplir EXACTAMENTE y
 * que fallan en silencio si se hacen distinto:
 *
 *   1. La ruta es `/TokenEndpoint`.
 *   2. Las credenciales van por HTTP Basic, NO en el cuerpo.
 *   3. El cuerpo va como `application/x-www-form-urlencoded`, no como JSON.
 *
 * Mandar un JSON con `clientId`/`clientSecret` no falla ruidosamente: Xubio
 * contesta `400 invalid_client`, que se lee como "las credenciales estan mal"
 * cuando estan bien y se mandaron donde no era.
 *
 * Con mas de una empresa hay N pedidos de token, y si cada uno reimplementa el
 * Basic a mano, un dia uno lo reimplementa mal y solo falla para la empresa nueva.
 * Una sola copia de la regla, testeada una vez.
 *
 * Sin estado y sin dependencias: son funciones puras sobre el error y sobre dos
 * strings.
 */
final class XubioCredenciales {

    private XubioCredenciales() {
    }

    /**
     * HTTP Basic con client-id y client-secret.
     *
     * Base64 de "id:secreto", que es lo que dice la documentacion de Xubio y lo que
     * hace `--user` de curl.
     *
     * No se parsea NADA de los dos strings: se concatenan con el dos puntos y listo.
     * Se estaba haciendo `id + secreto.substring(secreto.lastIndexOf(':'))`, que
     * descarta la parte del secreto ANTERIOR al ultimo dos puntos: con el secreto
     * "abc:def" mandaba "id:def". El dos puntos es un separador que se AGREGA, no algo
     * que haya que buscar adentro de los valores: un secreto de Xubio es opaco y
     * puede traer dos puntos, y un id tambien. Cortar en el ultimo ":", ademas, es
     * indistinguible de cortar en el primero.
     */
    static String basic(String id, String secreto) {
        String credenciales = id + ":" + secreto;
        return "Basic " + Base64.getEncoder()
                .encodeToString(credenciales.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * El cuerpo del error, que es donde la API dice que esta mal.
     *
     * Se recorta a 300 caracteres: un error de proxy puede devolver una pagina
     * HTML entera, y eso en un mensaje de la pantalla es ruido.
     */
    static String cuerpoDeError(RestClientResponseException e) {
        String cuerpo = e.getResponseBodyAsString();
        if (cuerpo == null || cuerpo.isBlank()) {
            return "sin detalle.";
        }
        String recortado = cuerpo.length() > 300 ? cuerpo.substring(0, 300) + "..." : cuerpo;
        return recortado.replaceAll("\\s+", " ").trim();
    }
}
