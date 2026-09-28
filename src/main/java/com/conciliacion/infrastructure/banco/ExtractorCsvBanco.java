package com.conciliacion.infrastructure.banco;

import com.conciliacion.application.banco.ExtractorBancario;
import com.conciliacion.application.banco.MovimientoBancoCrudo;
import com.conciliacion.application.banco.RangoFechas;
import com.conciliacion.domain.model.CuentaBancaria;
import com.conciliacion.domain.model.OrigenMovimiento;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lee un extracto en CSV que baja el usuario del home banking. No habla con ningun
 * banco, asi que no necesita token ni permiso: es el camino que siempre va a haber.
 *
 * Va por el mismo puerto que las APIs, y por eso sirve de prueba de que el puerto
 * aguanta. Cuando un extractor de Galicia entre, la deduplicacion, el registro de la
 * corrida y el link a la cuenta son los mismos.
 *
 * ── FORMATO ESPERADO ──────────────────────────────────────────────────────────
 * Cada banco exporta el suyo, asi que se aceptan las columnas con estos nombres
 * (con o sin tildes, con o sin espacios, en mayusculas):
 *
 *   fecha (obligatoria)   detalle (obligatoria)   importe (obligatorio, con signo)
 *   fecha_operacion       es_credito             comprobante            saldo
 *
 * El separador se detecta solo: ; , o tabulador. Si hay dos filas de encabezado se
 * ignoran. Si el `importe` viene negativo, eso define el signo y no hace falta la
 * columna `es_credito`.
 *
 * Lo que NO hace, y hay que aclarar porque se pide siempre:
 *  - No adivina el encoding. Se prueba UTF-8 y, si el texto trae caracteres de
 *    reemplazo, se relee como Windows-1252 (que es con lo que exporta la mayoria
 *    de los home banking). Un archivo que abre bien en Excel y llega con "REPARACI�N"
 *    es eso.
 *  - No inventa un comprobante. Si la columna no viene, `comprobante` queda null y
 *    por lo tanto esa fuente NO deduplica. Es el limite real de trabajar con CSV y
 *    la razon por la que las APIs son preferibles: el banco ya tiene el ID.
 */
@Component
public class ExtractorCsvBanco implements ExtractorBancario {

    private static final DateTimeFormatter[] FECHAS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyyMMdd")};

    @Override
    public String codigo() { return "CSV"; }

    @Override
    public String descripcion() { return "Archivo CSV del home banking"; }

    @Override
    public OrigenMovimiento origen() { return OrigenMovimiento.CSV; }

    @Override
    public boolean aceptaArchivo() { return true; }

    @Override
    public boolean puedeEjecutarseSolo() { return false; }

    @Override
    public List<MovimientoBancoCrudo> extraer(CuentaBancaria cuenta, RangoFechas rango) {
        throw new UnsupportedOperationException("CSV necesita un archivo: usar POST /importaciones/archivo");
    }

    @Override
    public List<MovimientoBancoCrudo> extraerDeArchivo(CuentaBancaria cuenta, RangoFechas rango,
                                                        byte[] contenido) {
        String texto = decodificar(contenido);
        List<String> lineas = new ArrayList<>();
        for (String l : texto.split("\\R")) {
            if (!l.isBlank()) lineas.add(l);
        }
        if (lineas.isEmpty()) return List.of();

        char sep = detectarSeparador(lineas.get(0));
        int primeraFila = 0;
        Map<String, Integer> cols = mapearColumnas(lineas.get(0), sep);
        if (cols.isEmpty()) {
            // Sin encabezado: se asume el orden de la documentacion de arriba.
            cols = new HashMap<>();
            cols.put("fecha", 0);
            cols.put("detalle", 1);
            cols.put("importe", 2);
            cols.put("es_credito", 3);
            cols.put("comprobante", 4);
            cols.put("saldo", 5);
        } else {
            primeraFila = 1;
        }

        List<MovimientoBancoCrudo> salida = new ArrayList<>();
        for (int i = primeraFila; i < lineas.size(); i++) {
            String[] c = lineas.get(i).split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1);
            // Fila basura: no se aborta la importacion, se sigue con la siguiente.
            try {
                MovimientoBancoCrudo crudo = convertir(c, cols);
                if (crudo == null) continue;
                if (rango.desde() != null && crudo.fecha().isBefore(rango.desde())) continue;
                if (rango.hasta() != null && crudo.fecha().isAfter(rango.hasta())) continue;
                salida.add(crudo);
            } catch (RuntimeException ignorada) {
                // Ver BancoIngestionService.soloValidos: una fila rota no puede tirar
                // abajo las 300 que si vinieron bien.
            }
        }
        return salida;
    }

    private MovimientoBancoCrudo convertir(String[] c, Map<String, Integer> cols) {
        LocalDate fecha = parseFecha(valor(c, cols, "fecha"));
        if (fecha == null) return null;
        String detalle = valor(c, cols, "detalle");
        if (detalle == null || detalle.isBlank()) detalle = "MOVIMIENTO SIN DETALLE";

        BigDecimal bruto = new BigDecimal(valor(c, cols, "importe").replace(".", "").replace(",", "."));
        boolean esCredito;
        String columnaSigno = valor(c, cols, "es_credito");
        if (columnaSigno != null && !columnaSigno.isBlank()) {
            esCredito = columnaSigno.trim().toUpperCase(Locale.ROOT).startsWith("C");
        } else {
            esCredito = bruto.signum() >= 0;
        }

        LocalDate operacion = parseFecha(valor(c, cols, "fecha_operacion"));
        String comprobante = valor(c, cols, "comprobante");
        String saldoTxt = valor(c, cols, "saldo");
        BigDecimal saldo = saldoTxt == null || saldoTxt.isBlank() ? null
                : new BigDecimal(saldoTxt.replace(".", "").replace(",", "."));

        return new MovimientoBancoCrudo(fecha, operacion, detalle,
                bruto.abs(), esCredito, comprobante, saldo);
    }

    private String valor(String[] c, Map<String, Integer> cols, String nombre) {
        Integer i = cols.get(nombre);
        if (i == null || i >= c.length) return null;
        String v = c[i].trim();
        return v.isEmpty() ? null : v;
    }

    private LocalDate parseFecha(String s) {
        if (s == null) return null;
        for (DateTimeFormatter f : FECHAS) {
            try {
                return LocalDate.parse(s, f);
            } catch (RuntimeException ignorada) {
                // siguiente formato
            }
        }
        return null;
    }

    /**
     * Los home banking.exportan en latin-1/Windows-1252 bastante seguido. UTF-8
     * decodifica eso sin quejarse y deja U+FFFD en cada caracter alto, que es como
     * el usuario ve "REPARACI�N".
     */
    private String decodificar(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("�")) return utf8;
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private char detectarSeparador(String linea) {
        for (char c : new char[]{';', '\t', ','}) {
            if (linea.indexOf(c) >= 0) return c;
        }
        return ';';
    }

    /** Normaliza el nombre de la columna: sin tildes, sin espacios, en minusculas. */
    private String normalizar(String s) {
        return s == null ? "" : Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[\\s_-]", "");
    }

    /**
     * Mapea encabezado -> indice. La clave del mapa es SIEMPRE el nombre canonico
     * que despues busca el resto del parser: mapear "Fecha Operacion" a la clave
     * "Fecha Operacion" hacia que el valor se buscara con otro nombre y salia null.
     *
     * "fecha" gana si el archivo trae dos columnas parecidas (Fecha y Fecha Operacion
     * con espacios de sobra), porque la fecha valor es la que usa el filtro de pantalla.
     */
    private Map<String, Integer> mapearColumnas(String encabezado, char sep) {
        String[] nombres = encabezado.split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1);
        Map<String, Integer> mapa = new HashMap<>();
        for (int i = 0; i < nombres.length; i++) {
            String n = normalizar(nombres[i]);
            if (n.equals("fecha") && !mapa.containsKey("fecha")) {
                mapa.put("fecha", i);
            } else if ((n.equals("fechaoperacion") || n.equals("fechaefectiva"))
                    && !mapa.containsKey("fecha_operacion")) {
                mapa.put("fecha_operacion", i);
            } else if ((n.equals("detalle") || n.equals("concepto") || n.equals("descripcion")
                    || n.equals("glosa")) && !mapa.containsKey("detalle")) {
                mapa.put("detalle", i);
            } else if ((n.equals("importe") || n.equals("monto") || n.equals("importears"))
                    && !mapa.containsKey("importe")) {
                mapa.put("importe", i);
            } else if ((n.equals("escredito") || n.equals("signo") || n.equals("tipo"))
                    && !mapa.containsKey("es_credito")) {
                mapa.put("es_credito", i);
            } else if ((n.equals("comprobante") || n.equals("id") || n.equals("idtransaccion")
                    || n.equals("numerodemovimiento")) && !mapa.containsKey("comprobante")) {
                mapa.put("comprobante", i);
            } else if (n.equals("saldo") && !mapa.containsKey("saldo")) {
                mapa.put("saldo", i);
            }
        }
        // Sin fecha o sin importe no es un extracto: se trata como archivo sin encabezado.
        if (!mapa.containsKey("fecha") || !mapa.containsKey("importe")) return new HashMap<>();
        return mapa;
    }
}
