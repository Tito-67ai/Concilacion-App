package com.conciliacion.infrastructure.xubio;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * El cliente HTTP de Xubio, con timeout.
 *
 * ── POR QUE UN BEAN Y NO `new RestTemplate()` DENTRO DEL SERVICIO ─────────────
 *
 * Por el timeout, sobre todo. El `new RestTemplate()` del snippet no tiene
 * ninguno, y el default de `SimpleClientHttpRequestFactory` es no tener timeout:
 * se conecta, manda el pedido, y espera para siempre. Un Xubio que acepta la
 * conexion y se cuelga deja el GET esperando, y con el la pantalla de
 * conciliacion entera, sin spinner y sin error. El usuario no puede ni diferenciar
 * eso de un problema de red propio.
 *
 * Un bean con timeout corta a los 10 segundos (configurables) y el error se
 * traduce a un mensaje que dice "no respondio a tiempo". Que es la diferencia
 * entre una app que te dice que paso y una que se queda muda.
 *
 * Los timeouts van en las DOS capas que existen, y por que:
 *  - connectTimeout: no se pudo establecer la conexion. Corta antes de esperar.
 *  - readTimeout: se conecto pero no contesta. Es el caso comun cuando el
 *    servicio esta caido.
 *
 * Y el connect se deja MAS corto que el read a proposito: si no hay red, no
 * hay nada que esperar 10 segundos para confirmarlo.
 */
@Configuration
@EnableConfigurationProperties(XubioProperties.class)
public class XubioHttpConfig {

    @Bean
    public RestClient xubioHttp(XubioProperties props) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(props.getTimeout())
                .withReadTimeout(props.getTimeout());

        RestClient.Builder builder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings));

        // El baseUrl va en configuracion, no escrito aca: la URL real depende del
        // plan y del ambiente, y si se cambia no tiene que haber recompilado.
        //
        // Solo se fija si hay algo. Con Xubio apagado (el estado por defecto) el
        // base-url esta vacio, y el bean se arma igual en el arranque: si
        // `baseUrl("")` se dejara pasar, la app no levantaria con la fuente
        // apagada, que es justamente cuando NO se usa.
        //
        // Y esto NO es un detalle: sin baseUrl, `uri("/TokenEndpoint")` revienta
        // con "URI is not absolute" en la PRIMERA llamada, no al arrancar. Estuvo
        // asi desde que se escribio, y no se vio porque sin credenciales el
        // cliente cortaba antes, con un mensaje claro. Con credenciales de prueba
        // el fallo aparece y parece de Xubio, cuando es de aca.
        if (props.getBaseUrl() != null && !props.getBaseUrl().isBlank()) {
            builder.baseUrl(props.getBaseUrl());
        }

        return builder.build();
    }
}
