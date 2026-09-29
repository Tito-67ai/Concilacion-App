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

        return RestClient.builder()
                // El baseUrl NO se fija aca a proposito. El snippet lo tiene escrito
                // ("https://xubio.com/API/1.1") y es un supuesto: la URL real
                // depende del plan y del ambiente. Va en configuracion, y si se
                // cambia no hay que recompilar.
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
