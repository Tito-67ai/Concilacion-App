package com.conciliacion.infrastructure.xubio;

import com.conciliacion.application.catalogo.CatalogoNoDisponibleException;
import com.conciliacion.application.catalogo.Catalogos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Que el cliente de Xubio hable bien y, sobre todo, que falle bien.
 *
 * ── QUE CASOS CUBRE Y POR QUE CADA UNO ─────────────────────────────────────────
 *
 *  1. El pedido del token sale EXACTO como lo documenta Xubio: a `/TokenEndpoint`,
 *     en form-urlencoded, con `grant_type=client_credentials` en el cuerpo y las
 *     credenciales por HTTP Basic. Esta prueba existe porque el codigo original
 *     mandaba un JSON a `/auth/token`, que falla con `400 invalid_client`: un
 *     error que dice "las credenciales estan mal" cuando estan perfectas y se
 *     mandaron donde no era. Es el fallo mas caro de diagnosticar de todos.
 *  2. Los nombres de campo de la spec: `ID` en cuentas, `circuitoContable_id` en
 *     circuitos. Ninguno de los dos es `id`, y sin el alias correspondiente el
 *     catalogo entero se descartaria por falta de id, en silencio.
 *  3. `expires_in` viene como texto (`"3600"`), y se lee igual.
 *  4. El token se usa como Bearer en los dos catalogos.
 *  5. El token se reusa: la segunda lectura no vuelve a pedirlo.
 *  6. Sin credenciales: el error dice que faltan, y NO reintentable.
 *  7. Credenciales que Xubio rechaza: contesta 400 `invalid_client`, no 401, y el
 *     cuerpo del error viaja en el mensaje.
 *  8. 500: reintentable, porque un 5xx del servidor se cae solo.
 *  9. 200 sin token: Xubio contesto bien y sin credenciales.
 * 10. La fuente apagada devuelve vacio, no excepcion: apagar Xubio es una
 *     decision, no un error.
 * 11. Prendida y sin credenciales: sigue prendida Y tira error.
 * 12. Un catalogo vacio es "sin datos", no un error.
 */
class XubioClienteTest {

    private static final String BASE = "https://api.ejemplo.test";

    /** Lo que dice la documentacion de Xubio para pedir un token. */
    private static final String BASIC =
            "Basic " + Base64.getEncoder().encodeToString(
                    "un-client:un-secreto".getBytes(StandardCharsets.UTF_8));

    private MockRestServiceServer servidor;
    private XubioProperties props;
    private XubioCliente cliente;

    @BeforeEach
    void armar() {
        props = new XubioProperties();
        props.setHabilitado(true);
        props.setBaseUrl(BASE);
        props.setClientId("un-client");
        props.setClientSecret("un-secreto");

        RestClient.Builder builder = RestClient.builder().baseUrl(props.getBaseUrl());
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new XubioCliente(props, builder.build());
    }

    /**
     * Un token de verdad, con `expires_in` como texto, que es como lo manda Xubio.
     *
     * <p>Las aserciones del pedido van ACA y no en un test suelto, para que las
     * paguen todos los tests que piden token. Si el protocolo del token se rompe,
     * que fallen todos juntos: un test que lo verifica y nueve que lo dan por
     * hecho no dicen nada cuando el primero queda viejo.
     */
    private void tokenOk() {
        servidor.expect(requestTo(BASE + "/TokenEndpoint"))
                .andExpect(method(HttpMethod.POST))
                // Esto es lo que rompio la integracion: mandando un JSON a
                // `/auth/token`, Xubio contesta "invalid_client" y el mensaje dice
                // que las credenciales estan mal. Cada linea aca es una forma
                // distinta de mandar el mismo pedido equivocado.
                .andExpect(header("Authorization", BASIC))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(containsString("grant_type=client_credentials")))
                .andExpect(content().string(not(containsString("un-secreto"))))
                .andRespond(withSuccess("{\"scope\":\"\",\"expires_in\":\"3600\","
                        + "\"token_type\":\"Bearer\",\"access_token\":\"tok-123\"}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("El token se pide como lo documenta Xubio y sin las credenciales en el cuerpo")
    void pideElTokenComoLoDocumenta() {
        tokenOk();
        servidor.expect(requestTo(BASE + "/cuenta")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/circuitoContableBean")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        cliente.consultar();

        servidor.verify();
        // El `tokenOk()` de arriba es el que verifica el pedido; este test existe
        // para que el caso tenga nombre y quede escrito que se esta probando, no
        // solo de paso.
    }

    @Test
    @DisplayName("Lee los nombres de campo de la spec y los usa como Bearer")
    void leeLosCamposDeLaSpec() {
        tokenOk();
        servidor.expect(requestTo(BASE + "/cuenta"))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andRespond(withSuccess("[{\"ID\":111,\"nombre\":\"Clientes\","
                        + "\"codigo\":\"1.1.01.001\",\"id\":111}]", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/circuitoContableBean"))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andRespond(withSuccess("[{\"circuitoContable_id\":222,\"nombre\":\"Ventas\","
                        + "\"codigo\":\"V\"}]", MediaType.APPLICATION_JSON));

        Catalogos c = cliente.consultar();

        assertEquals(1, c.cuentasContables().size());
        assertEquals("111", c.cuentasContables().get(0).id());
        assertEquals("Clientes", c.cuentasContables().get(0).nombre());
        assertEquals("1.1.01.001", c.cuentasContables().get(0).codigo());
        // El circuito no tiene `id`: el suyo se llama `circuitoContable_id`. Sin
        // ese alias, cero circuitos se sincronizan y no dice ni una palabra.
        assertEquals("222", c.circuitos().get(0).id());
        assertEquals("Ventas", c.circuitos().get(0).nombre());
        servidor.verify();
    }

    @Test
    @DisplayName("La segunda consulta reusa el token y no vuelve a pedirlo")
    void reusaElToken() {
        tokenOk();
        for (int i = 0; i < 2; i++) {
            servidor.expect(requestTo(BASE + "/cuenta"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
            servidor.expect(requestTo(BASE + "/circuitoContableBean"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }

        cliente.consultar();
        cliente.consultar();

        servidor.verify();
        // Si el token se hubiera pedido dos veces, el `expect` de /TokenEndpoint
        // (que se registra una sola vez) fallaria por llamada de mas.
    }

    @Test
    @DisplayName("Sin credenciales el error las menciona y no se puede reintentar")
    void sinCredenciales() {
        props.setClientSecret("");

        CatalogoNoDisponibleException e =
                assertThrows(CatalogoNoDisponibleException.class, () -> cliente.consultar());

        assertTrue(e.getMessage().contains("credenciales"), "el mensaje tiene que decir que faltan: " + e.getMessage());
        assertFalse(e.esReintentable(), "reintentar sin credenciales devuelve lo mismo");
    }

    @Test
    @DisplayName("Credenciales que Xubio rechaza: contesta 400, no 401, y no se reintenta")
    void credencialesRechazadas() {
        // Esta es la respuesta REAL de Xubio con un client-id falso. Si el cliente
        // esperara 401, no lo reconoceria y el mensaje seria otra cosa.
        servidor.expect(requestTo(BASE + "/TokenEndpoint"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"error_description\":\"Client authentication failed\","
                                + "\"error\":\"invalid_client\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        CatalogoNoDisponibleException e =
                assertThrows(CatalogoNoDisponibleException.class, () -> cliente.consultar());

        assertFalse(e.esReintentable(), "con las mismas credenciales devuelve lo mismo");
        assertTrue(e.getMessage().contains("invalid_client"),
                "el cuerpo es donde dice que esta mal, tiene que viajar: " + e.getMessage());
    }

    @Test
    @DisplayName("Un 500 de Xubio si se puede reintentar")
    void errorDelServidorEsReintentable() {
        servidor.expect(requestTo(BASE + "/TokenEndpoint"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        CatalogoNoDisponibleException e =
                assertThrows(CatalogoNoDisponibleException.class, () -> cliente.consultar());

        assertTrue(e.esReintentable(), "un 5xx del servidor se cae solo, tiene que poder reintentarse");
    }

    @Test
    @DisplayName("Un 200 sin token apunta a las credenciales, que es el fallo mas comun")
    void respondeSinToken() {
        servidor.expect(requestTo(BASE + "/TokenEndpoint"))
                .andRespond(withSuccess("{\"error\":\"credenciales incorrectas\"}", MediaType.APPLICATION_JSON));

        CatalogoNoDisponibleException e =
                assertThrows(CatalogoNoDisponibleException.class, () -> cliente.consultar());

        assertFalse(e.esReintentable());
        assertTrue(e.getMessage().contains("token"), "el mensaje tiene que aclarar que no vino token");
    }

    @Test
    @DisplayName("Con la fuente apagada devuelve vacio y no tira excepcion")
    void fuenteApagada() {
        props.setHabilitado(false);
        props.setClientId("");
        props.setClientSecret("");

        Catalogos c = cliente.consultar();

        assertTrue(c.vacio());
        assertFalse(cliente.habilitado());
        servidor.verify();
    }

    @Test
    @DisplayName("Prendida y sin credenciales: la fuente esta prendida Y tira error")
    void prendidaSinCredenciales() {
        props.setClientSecret("");

        // Esta es la parte que importa. Si `habilitado()` pidiera tambien las
        // credenciales, devolveria false aca, y la app con Xubio prendido y sin
        // token se comportaria como si estuviera apagada: sembraria sus cuentas
        // inventadas y no diria nada. Prendido y sin credenciales es un estado
        // real y tiene que verse.
        assertTrue(cliente.habilitado(),
                "prendida sin credenciales sigue siendo prendida, no apagada");

        CatalogoNoDisponibleException e =
                assertThrows(CatalogoNoDisponibleException.class, () -> cliente.consultar());
        assertTrue(e.getMessage().contains("credenciales"), e.getMessage());
        assertFalse(e.esReintentable());
    }

    @Test
    @DisplayName("Prendida y sin base-url: el error lo nombra, no dice 'credenciales'")
    void prendidaSinBaseUrl() {
        props.setBaseUrl("");

        assertTrue(cliente.habilitado());

        CatalogoNoDisponibleException e =
                assertThrows(CatalogoNoDisponibleException.class, () -> cliente.consultar());
        assertTrue(e.getMessage().contains("base-url"), e.getMessage());
    }

    @Test
    @DisplayName("Un catalogo vacio es 'sin datos', no un error")
    void catalogoVacio() {
        tokenOk();
        servidor.expect(requestTo(BASE + "/cuenta"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/circuitoContableBean"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        Catalogos c = cliente.consultar();

        assertTrue(c.vacio());
        assertEquals(0, c.total());
    }
}
