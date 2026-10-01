package com.conciliacion.infrastructure.xubio;

import com.conciliacion.application.empresa.Empresa;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Que la barra de empresas arme bien la lista.
 *
 * ── QUE CASOS CUBRE, Y POR QUE CADA UN ────────────────────────────────────────
 *
 *  1. El nombre sale de `GET /miempresa`, no de la configuracion. Es la razon de
 *     que esta clase exista: si el nombre fuera un campo del yml, la lista
 *     mentiria en silencio respecto de Xubio.
 *  2. Cada empresa pide SU token. Un token compartido entre empresas daria acceso a
 *     la empresa equivocada en silencio, que es el peor fallo posible aca.
 *  3. Una empresa con credenciales malas NO desaparece: vuelve con estado
 *     SIN_ACCESO y el motivo. Filtrarla haria que una credencial vencida se viera
 *     como una empresa borrada.
 *  4. Una sola empresa caida no impide ver las otras. Si se propagara la excepcion,
 *     una App Cliente mal pegada dejaria la app entera sin empresas.
 *  5. Sin credenciales, la entrada sigue en la lista diciendo que le faltan.
 *  6. Xubio apagado devuelve vacio, que es un estado normal y no un error.
 *  7. Una `clave` repetida o vacia se ignora con un warning en vez de romper el
 *     desplegable, donde la `clave` es el valor de cada `<option>`.
 *
 * ── POR QUE UN SERVIDOR DE VERDAD Y NO UN MOCK ────────────────────────────────
 *
 * Porque lo que hay que mirar aca son las peticiones que SALEN: el Basic del
 * token, que el token sea el de la empresa correcta, y que `/miempresa` se pida
 * una vez por empresa. Un `MockRestServiceServer` verifica lo que entra; que cada
 * empresa use sus propias credenciales se verifica mirando a donde fue cada
 * pedido con su cabecera.
 */
class XubioEmpresasTest {

    /** Respuestas por Authorization, para poder fallar solo una empresa. */
    private final Map<String, String> nombresPorToken = new ConcurrentHashMap<>();

    private XubioEmpresas armar(String clientId, String secret,
                                List<XubioProperties.EmpresaXubio> extra) throws IOException {
        List<String> pedidos = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String ruta = exchange.getRequestURI().getPath();
            String auth = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
            pedidos.add(ruta + " " + auth);
            responder(exchange, ruta, auth);
        });
        server.start();
        try {
            XubioProperties props = new XubioProperties();
            props.setHabilitado(true);
            props.setBaseUrl("http://localhost:" + server.getAddress().getPort());
            props.setClientId(clientId);
            props.setClientSecret(secret);
            props.setEmpresas(extra);
            return new XubioEmpresas(props, new XubioHttpConfig().xubioHttp(props));
        } finally {
            // El servidor se corta en cada test; se registra el stop en el tearDown
            // de cada caso para no dejar hilos colgados.
            servers.add(server);
        }
    }

    private final List<HttpServer> servers = new ArrayList<>();

    private void responder(com.sun.net.httpserver.HttpExchange exchange, String ruta, String auth)
            throws IOException {
        String cuerpo;
        if ("/TokenEndpoint".equals(ruta)) {
            // El token ES la Basic que llego: asi el test puede verificar que cada
            // empresa pidio con la suya.
            cuerpo = "{\"expires_in\":\"3600\",\"token_type\":\"Bearer\",\"access_token\":\"" + auth + "\"}";
        } else if ("/miempresa".equals(ruta)) {
            String empresa = nombresPorToken.get(auth);
            if (empresa == null) {
                byte[] bytes = "{\"error\":\"invalid_client\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, bytes.length);
                try (OutputStream salida = exchange.getResponseBody()) {
                    salida.write(bytes);
                }
                return;
            }
            cuerpo = "{\"nombreEmpresa\":\"" + empresa + "\",\"cuit\":\"30-12345678-9\"}";
        } else {
            cuerpo = "[]";
        }
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream salida = exchange.getResponseBody()) {
            salida.write(bytes);
        }
    }

    private XubioProperties.EmpresaXubio empresa(String clave, String id, String secreto) {
        XubioProperties.EmpresaXubio e = new XubioProperties.EmpresaXubio();
        e.setClave(clave);
        e.setClientId(id);
        e.setClientSecret(secreto);
        return e;
    }

    @Test
    @DisplayName("El nombre de cada empresa lo trae /miempresa de Xubio")
    void elNombreVieneDeXubio() throws IOException {
        nombresPorToken.put(conTokenDe("cli-principal", "sec-principal"), "Ferreteria Tito S.R.L.");
        XubioEmpresas empresas = armar("cli-principal", "sec-principal", List.of());

        List<Empresa> lista = empresas.consultar();

        assertEquals(1, lista.size());
        assertEquals("default", lista.get(0).clave());
        assertEquals("Ferreteria Tito S.R.L.", lista.get(0).nombre());
        assertEquals(Empresa.OK, lista.get(0).estado());
    }

    @Test
    @DisplayName("Cada empresa pide su propio token: uno compartido daria acceso a la empresa que no es")
    void cadaEmpresaUsaSuCredencial() throws IOException {
        nombresPorToken.put(conTokenDe("cli-a", "sec-a"), "Empresa A");
        nombresPorToken.put(conTokenDe("cli-b", "sec-b"), "Empresa B");

        List<String> pedidos = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String ruta = exchange.getRequestURI().getPath();
            String auth = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
            pedidos.add(ruta + " " + auth);
            responder(exchange, ruta, auth);
        });
        server.start();
        servers.add(server);

        XubioProperties props = new XubioProperties();
        props.setHabilitado(true);
        props.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        props.setEmpresas(List.of(empresa("a", "cli-a", "sec-a"), empresa("b", "cli-b", "sec-b")));

        List<Empresa> lista = new XubioEmpresas(props, new XubioHttpConfig().xubioHttp(props)).consultar();

        assertEquals(List.of("a", "b"), lista.stream().map(Empresa::clave).toList());
        assertEquals(List.of("Empresa A", "Empresa B"), lista.stream().map(Empresa::nombre).toList());
        // Dos tokens, dos /miempresa. Si el token fuera compartido, aca habria un solo
        // pedido de token y las dos empresas saldrian con el mismo nombre.
        assertEquals(2, pedidos.stream().filter(p -> p.startsWith("/TokenEndpoint")).count());
        assertEquals(2, pedidos.stream().filter(p -> p.startsWith("/miempresa")).count());    }

    @Test
    @DisplayName("Una empresa sin acceso sigue en la lista, con el motivo")
    void laSinAccesoNoDesaparece() throws IOException {
        nombresPorToken.put(conTokenDe("cli-ok", "sec-ok"), "Empresa que anda");
        XubioEmpresas empresas = armar("cli-ok", "sec-ok",
                List.of(empresa("rota", "cli-roto", "sec-malo")));

        List<Empresa> lista = empresas.consultar();

        assertEquals(2, lista.size());
        Empresa rota = lista.stream().filter(e -> e.clave().equals("rota")).findFirst().orElseThrow();
        assertEquals(Empresa.SIN_ACCESO, rota.estado());
        // El motivo se muestra en la pantalla: "no se pudo consultar" sin decir cual
        // ni por que deja al usuario sin nada que hacer.
        assertTrue(rota.detalle().contains("rota"), "el motivo nombra la empresa: " + rota.detalle());
        // Y la que funciona no se ve afectada.
        assertEquals(Empresa.OK, lista.stream().filter(e -> e.clave().equals("default"))
                .findFirst().orElseThrow().estado());
    }

    @Test
    @DisplayName("Una empresa sin credenciales declaradas se avisa, no se calla")
    void sinCredencialesDeclaradas() throws IOException {
        nombresPorToken.put(conTokenDe("cli-principal", "sec-principal"), "La principal");
        XubioEmpresas empresas = armar("cli-principal", "sec-principal",
                List.of(empresa("incompleta", "", "")));

        List<Empresa> lista = empresas.consultar();

        Empresa incompleta = lista.stream().filter(e -> e.clave().equals("incompleta"))
                .findFirst().orElseThrow();
        assertEquals(Empresa.SIN_ACCESO, incompleta.estado());
        assertTrue(incompleta.detalle().toLowerCase().contains("credenciales"), incompleta.detalle());
    }

    @Test
    @DisplayName("Con Xubio apagado la lista es vacia, no un error")
    void sinFuentePuestaDevuelveVacio() {
        XubioProperties props = new XubioProperties(); // habilitado = false

        List<Empresa> lista = new XubioEmpresas(props, new XubioHttpConfig().xubioHttp(props)).consultar();

        assertTrue(lista.isEmpty());
    }

    @Test
    @DisplayName("Una clave repetida se ignora: es el valor de cada opcion del desplegable")
    void claveRepetidaSeIgnora() throws IOException {
        nombresPorToken.put(conTokenDe("cli-ok", "sec-ok"), "La principal");
        XubioEmpresas empresas = armar("cli-ok", "sec-ok",
                List.of(empresa("tito", "cli-a", "sec-a"), empresa("tito", "cli-b", "sec-b")));

        List<Empresa> lista = empresas.consultar();

        assertEquals(2, lista.size());
        assertEquals(1, lista.stream().filter(e -> e.clave().equals("tito")).count());
    }

    @Test
    @DisplayName("Una entrada sin clave no se inventa una: es un error de configuracion")
    void claveVaciaSeIgnora() throws IOException {
        nombresPorToken.put(conTokenDe("cli-ok", "sec-ok"), "La principal");
        XubioEmpresas empresas = armar("cli-ok", "sec-ok",
                List.of(empresa("  ", "cli-a", "sec-a"), empresa("buena", "cli-b", "sec-b")));

        List<Empresa> lista = empresas.consultar();

        assertEquals(List.of("default", "buena"), lista.stream().map(Empresa::clave).toList());
    }

    /**
     * El Basic tiene que ser exactamente "id:secreto".
     *
     * ── POR QUE ESTE TEST Y NO AGREGARLE UN CASO A LOS DE ARRIBA ───────────────
     *
     * Porque los de arriba no lo pueden cazar. Comparan `conTokenDe(id, secreto)`,
     * que llama a `basic(...)`, contra lo que el servidor recibio: los dos lados
     * salen de la MISMA funcion, asi que si `basic` parte mal el secreto los dos
     * quedan mal por igual y el test pasa. Un test que usa el codigo bajo prueba
     * para armar la expectativa no verifica nada.
     *
     * Aca el Base64 se DECODIFICA y se compara contra el string crudo, asi que la
     * expectativa no depende de `basic`. Y el caso interesante es el secreto con
     * dos puntos: es opaco y puede traerlos, y la version anterior hacia
     * `id + secreto.substring(secreto.lastIndexOf(':'))`, o sea descartaba todo lo
     * del secreto ANTERIOR al ultimo dos puntos y mandaba "id:def" en vez de
     * "id:abc:def".
     */
    @Test
    @DisplayName("El Basic es id:secreto tal cual, aunque el secreto traiga dos puntos")
    void elBasicEsIdYSecretoTalCual() {
        assertEquals("Basic " + base64("cli-ok:sec-ok"),
                XubioCredenciales.basic("cli-ok", "sec-ok"));

        assertEquals("Basic " + base64("cli-ok:abc:def"),
                XubioCredenciales.basic("cli-ok", "abc:def"));

        // El id tambien puede traerlos: el dos puntos va entre los dos y solo uno.
        assertEquals("Basic " + base64("cli:con:puntos:abc:def"),
                XubioCredenciales.basic("cli:con:puntos", "abc:def"));
    }

    /** El mismo Base64, escrito aca para no volver a llamar a la funcion bajo prueba. */
    private static String base64(String texto) {
        return java.util.Base64.getEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }

    @org.junit.jupiter.api.AfterEach
    void cerrarServidores() {
        for (HttpServer s : servers) {
            s.stop(0);
        }
        servers.clear();
    }

    /**
     * La cabecera COMPLETA con la que una empresa llega a `/miempresa`.
     *
     * El servidor devuelve como token la Basic del pedido de token, asi que en la
     * llamada a `/miempresa` el header es `Bearer Basic Y2xp...`. La clave del mapa
     * tiene el `Bearer ` adelante, y sin el test falla por una razon que no tiene
     * nada que ver con lo que quiere verificar.
     */
    private static String conTokenDe(String id, String secreto) {
        return "Bearer " + XubioCredenciales.basic(id, secreto);
    }

    private static String basicDe(String id, String secreto) {
        return XubioCredenciales.basic(id, secreto);
    }
}
