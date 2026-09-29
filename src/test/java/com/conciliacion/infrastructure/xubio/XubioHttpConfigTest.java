package com.conciliacion.infrastructure.xubio;

import com.conciliacion.application.catalogo.Catalogos;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Que el cliente HTTP arme bien las direcciones.
 *
 * ── POR QUE ESTE TEST USA UN SERVIDOR DE VERDAD ───────────────────────────────
 *
 * Porque el bug que fija aca no se puede ver con un mock. `XubioClienteTest`
 * construye su propio `RestClient` con `.baseUrl(...)` a mano, asi que para esa
 * prueba el baseUrl siempre estuvo bien puesto. Y en produccion no lo estaba:
 * `XubioHttpConfig` nunca se lo pasaba al builder, y todas las peticiones se
 * quedaban en rutas relativas que no tienen a donde ir.
 *
 * El sintoma era `URI is not absolute`, que sale en la primera llamada y parece un
 * problema de Xubio. Y no aparecia en los tests porque con credenciales faltantes
 * el cliente cortaba antes, con un mensaje claro: el error de verdad estaba
 * tapado por un guard que si funcionaba.
 *
 * Levantar un servidor en un puerto libre y mirar que ruta llega es lo unico que
 * mide esto. Un mock de URL no mide si el bean le pasa el baseUrl al builder.
 *
 * ── QUE CASOS CUBRE ────────────────────────────────────────────────────────────
 *
 *  1. Con base-url configurado, las tres peticiones salen en la ruta correcta.
 *  2. Sin base-url (Xubio apagado, que es el estado por defecto) el bean se arma
 *     sin fallar. Si `baseUrl("")` se dejara pasar, la app no levantaria con la
 *     fuente apagada, que es justo cuando no se usa.
 */
class XubioHttpConfigTest {

    @Test
    @DisplayName("Las rutas de la config se resuelven contra el base-url configurado")
    void resuelveLasRutasContraElBaseUrl() throws IOException {
        List<String> rutas = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String ruta = exchange.getRequestURI().getPath();
            rutas.add(ruta);
            String cuerpo = "/TokenEndpoint".equals(ruta)
                    ? "{\"expires_in\":\"3600\",\"token_type\":\"Bearer\",\"access_token\":\"tok\"}"
                    : "[]";
            byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream salida = exchange.getResponseBody()) {
                salida.write(bytes);
            }
        });
        server.start();

        try {
            XubioProperties props = new XubioProperties();
            props.setHabilitado(true);
            props.setBaseUrl("http://localhost:" + server.getAddress().getPort());
            props.setClientId("un-client");
            props.setClientSecret("un-secreto");

            // El bean de verdad de la app, no uno armado a mano. Si el test se
            // armara el suyo, estaria probando otra cosa.
            XubioCliente cliente = new XubioCliente(props, new XubioHttpConfig().xubioHttp(props));

            Catalogos c = cliente.consultar();

            assertEquals(List.of("/TokenEndpoint", "/cuenta", "/circuitoContableBean"), rutas);
            assertTrue(c.vacio());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Sin base-url el bean se arma igual: la app tiene que levantar apagada")
    void sinBaseUrlElBeanSeConstruye() {
        // Con Xubio apagado el base-url esta vacio, y ese es el estado por defecto:
        // el bean se arma en cada arranque. Si construirlo revantara, la app no
        // levanta nunca.
        XubioProperties props = new XubioProperties();

        assertDoesNotThrow(() -> new XubioHttpConfig().xubioHttp(props));
    }
}
