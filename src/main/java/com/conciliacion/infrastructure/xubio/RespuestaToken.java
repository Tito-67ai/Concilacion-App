package com.conciliacion.infrastructure.xubio;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lo que contesta Xubio al pedir el token.
 *
 * ── LA FORMA REAL, VERIFICADA ─────────────────────────────────────────────────
 *
 * Xubio documenta el pedido asi:
 *
 *   curl -X POST https://xubio.com/API/1.1/TokenEndpoint \
 *        -H "Content-Type: application/x-www-form-urlencoded" \
 *        -d 'grant_type=client_credentials' \
 *        --user TU_CLIENT_ID:TU_SECRET_ID
 *
 * y contesta:
 *
 *   {"scope":"","expires_in":"3600","token_type":"Bearer","access_token":"..."}
 *
 * ── POR QUE `expires_in` ES UN String Y NO UN Integer ─────────────────────────
 *
 * Porque Xubio lo manda entre comillas: es `"3600"`, no `3600`. Un Integer
 * tambien habria deserializado, porque Jackson convierte el numero en cadena por
 * su cuenta, y por eso el bug seria invisible en las pruebas con un `3600` a mano
 * y explotaria contra el servidor real con la respuesta de verdad.
 *
 * Asi que se declara String, que es lo que llega, y lo convierte `segundosDeVida`.
 * Un record fiel a la respuesta es el que se puede comparar contra la documentacion
 * de un vistazo; el que se parece a lo que uno imagina, no.
 *
 * ── POR QUE HAY ALIAS PARA EL MISMO CAMPO ──────────────────────────────────────
 *
 * El estandar OAuth2 pone `access_token`, y hay implementaciones que usan `token`
 * o `accessToken`. Aca se aceptan los tres: es una anotacion, y si Xubio un dia
 * cambia el nombre no hay que reescribir el cliente, se anota.
 *
 * La alternativa era un JsonNode y buscar a mano en tres claves, que ademas de
 * mas fragil tira el error en runtime. Con Jackson el error sale al deserializar,
 * en el log, con el nombre del campo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RespuestaToken {

    @JsonProperty("access_token")
    @JsonAlias({"token", "accessToken"})
    private String accessToken;

    /** Viene como texto: `{"expires_in":"3600"}`. */
    @JsonProperty("expires_in")
    @JsonAlias({"expiresIn"})
    private String expiresIn;

    @JsonProperty("token_type")
    @JsonAlias({"tokenType"})
    private String tokenType = "Bearer";

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getExpiresIn() { return expiresIn; }
    public void setExpiresIn(String expiresIn) { this.expiresIn = expiresIn; }

    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }

    /**
     * Los segundos de vida del token, o null si no vinieron o no son un numero.
     *
     * Se devuelve null y no un default a proposito: el que decide que hacer cuando
     * Xubio no dice cuando vence es el cliente, y esa decision (renovar cada
     * tanto, con un supuesto) tiene que quedar en el cliente y no escondida aca.
     * Un numero mal formado se trata como "no dijo", que es lo que es.
     */
    public Integer segundosDeVida() {
        if (expiresIn == null || expiresIn.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(expiresIn.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
