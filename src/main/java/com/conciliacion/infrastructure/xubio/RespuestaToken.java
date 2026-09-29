package com.conciliacion.infrastructure.xubio;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lo que contesta Xubio al pedir el token.
 *
 * ── POR QUE HAY TRES ALIAS PARA EL MISMO CAMPO ───────────────────────────────
 *
 * El snippet que motiva esto devuelve la respuesta cruda y pone
 * "TOKEN_EXTRAIDO_DEL_JSON", o sea que nunca llega a leer el token. Este record
 * si lo lee, y con tres nombres posibles, porque las APIs de OAuth ponen
 * `access_token` y otras veces `token`, y hay versiones que contestan
 * `accessToken`.
 *
 * La alternativa era un JsonNode y buscar a mano en tres claves, que ademas de
 * mas fragil tira el error en runtime. Con Jackson es un @JsonProperty por alias
 * y el error sale al deserializar, en el log, con el nombre del campo.
 *
 * No hay `expiresIn` en el record a proposito: si Xubio no lo manda, la
 * renovacion se hace por tiempo fijo, que es peor que usar la expiracion pero no
 * es romper. Si lo manda, se usa.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RespuestaToken {

    @JsonProperty("access_token")
    @JsonAlias({"token", "accessToken"})
    private String accessToken;

    @JsonProperty("expires_in")
    @JsonAlias({"expiresIn"})
    private Integer expiresIn;

    @JsonProperty("token_type")
    @JsonAlias({"tokenType"})
    private String tokenType = "Bearer";

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public Integer getExpiresIn() { return expiresIn; }
    public void setExpiresIn(Integer expiresIn) { this.expiresIn = expiresIn; }

    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }
}
