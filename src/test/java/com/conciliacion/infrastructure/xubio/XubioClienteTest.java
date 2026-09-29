package com.conciliacion.infrastructure.xubio;

import com.conciliacion.application.catalogo.CatalogoNoDisponibleException;
import com.conciliacion.application.catalogo.Catalogos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Que el cliente de Xubio hable bien y, sobre todo, que falle bien.
 *
 * ── POR QUE ESTE TEST NO PUEDE SABER SI LOS ENDPOINTS EXISTEN ──────────────────
 *
 * Porque no hay credenciales. Las rutas y los nombres de campo que se prueban
 * salen del ejemplo generico que motivo todo esto, y ese mismo ejemplo decia
 * "revisar en la documentacion la URL exacta". Este test verifica que el cliente
 * arma las peticiones y lee las respuestas de la forma que se eligio; no verifica
 * que `/cuentas-bancarias` sea la ruta correcta en Xubio, porque eso solo se
 * sabe preguntándole a Xubio.
 *
 * Lo que SI se puede probar sin credenciales, y es lo que importa, es que cada
 * forma de fallo llegue a la pantalla como un mensaje que dice que paso: eso no
 * depende de la API real.
 *
 * ── QUE CASOS CUBRE Y POR QUE CADA UNO ─────────────────────────────────────────
 *
 *  1. Pide el token, lo usa en las tres llamadas y lo manda como Bearer.
 *  2. El token se reusa: la segunda lectura de catalogos no vuelve a pedirlo. Sin
 *     esto, cada carga de la pantalla seria un token de mas.
 *  3. Sin credenciales: el error dice que faltan, y NO reintentable. Un boton de
 *     "reintentar" con credenciales malas no hace nada util.
 *  4. 401 de Xubio: no reintentable, y el cuerpo del error de Xubio viaja en el
 *     mensaje, que es donde la API dice que esta mal.
 *  5. 500 de Xubio: reintentable, porque un 5xx del servidor se cae solo.
 *  6. 200 sin token: Xubio contesto bien y sin credenciales. Es el fallo mas
 *     comun de todos y el que menos se entiende si el mensaje no lo aclara.
 *  7. La fuente apagada devuelve vacio en vez de tirar excepcion: apagar Xubio
 *     es una decision, no un error.
 */
class XubioClienteTest {

    private static final String BASE = "https://api.ejemplo.test";

    private MockRestServiceServer servidor;
    private XubioProperties props;
    private XubioCliente cliente;

    @BeforeEach
    void armar() {
        props = new XubioProperties();
        props.setHabilitado(true);
        props.setBaseUrl("https://api.ejemplo.test");
        props.setClientId("un-client");
        props.setClientSecret("un-secreto");

        RestClient.Builder builder = RestClient.builder().baseUrl(props.getBaseUrl());
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new XubioCliente(props, builder.build());
    }

    private void tokenOk() {
        servidor.expect(requestTo(BASE + "/auth/token"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"tok-123\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("Pide el token una vez y lo manda como Bearer en los tres catalogos")
    void pideTokenYLoUsa() {
        tokenOk();
        servidor.expect(requestTo(BASE + "/cuentas-bancarias"))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andRespond(withSuccess("[{\"id\":\"x-1\",\"nombre\":\"Cuenta\",\"codigo\":\"cbu-1\","
                        + "\"grupo\":\"Galicia\"}]", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/cuentas-contables"))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andRespond(withSuccess("[{\"id\":\"c-1\",\"nombre\":\"Clientes\","
                        + "\"codigo\":\"1.1.01.001\"}]", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/circuitos"))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andRespond(withSuccess("[{\"id\":\"k-1\",\"nombre\":\"Ventas\"}]", MediaType.APPLICATION_JSON));

        Catalogos c = cliente.consultar();

        assertEquals(1, c.cuentasBancarias().size());
        assertEquals("Galicia", c.cuentasBancarias().get(0).grupo());
        assertEquals("1.1.01.001", c.cuentasContables().get(0).codigo());
        assertEquals("Ventas", c.circuitos().get(0).nombre());
        servidor.verify();
    }

    @Test
    @DisplayName("La segunda consulta reusa el token y no vuelve a pedirlo")
    void reusaElToken() {
        tokenOk();
        for (int i = 0; i < 2; i++) {
            servidor.expect(requestTo(BASE + "/cuentas-bancarias"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
            servidor.expect(requestTo(BASE + "/cuentas-contables"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
            servidor.expect(requestTo(BASE + "/circuitos"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }

        cliente.consultar();
        cliente.consultar();

        servidor.verify();
        // Si el token se hubiera pedido dos veces, el `expect` de /auth/token
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
    @DisplayName("Un 401 de Xubio no se reintenta y trae el detalle que mando la API")
    void unauthorized() {
        servidor.expect(requestTo(BASE + "/auth/token"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .body("{\"error\":\"client invalido\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        CatalogoNoDisponibleException e =
                assertThrows(CatalogoNoDisponibleException.class, () -> cliente.consultar());

        assertFalse(e.esReintentable());
        assertTrue(e.getMessage().contains("401"));
        assertTrue(e.getMessage().contains("client invalido"),
                "el cuerpo de la API es donde dice que esta mal, tiene que viajar: " + e.getMessage());
    }

    @Test
    @DisplayName("Un 500 de Xubio si se puede reintentar")
    void errorDelServidorEsReintentable() {
        servidor.expect(requestTo(BASE + "/auth/token"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        CatalogoNoDisponibleException e =
                assertThrows(CatalogoNoDisponibleException.class, () -> cliente.consultar());

        assertTrue(e.esReintentable(), "un 5xx del servidor se cae solo, tiene que poder reintentarse");
    }

    @Test
    @DisplayName("Un 200 sin token apunta a las credenciales, que es el fallo mas comun")
    void respondeSinToken() {
        servidor.expect(requestTo(BASE + "/auth/token"))
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
        for (String ruta : List.of("/cuentas-bancarias", "/cuentas-contables", "/circuitos")) {
            servidor.expect(requestTo(BASE + ruta)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }

        Catalogos c = cliente.consultar();

        assertTrue(c.vacio());
        assertEquals(0, c.total());
    }
}
