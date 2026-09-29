package com.conciliacion.infrastructure.banco;

import com.conciliacion.application.banco.MovimientoBancoCrudo;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lo que tienen en comun TODOS los lectores de archivo del banco, sin importar si la
 * tabla vino de un CSV, de una hoja de Excel o de un PDF.
 *
 * Existe por una razon concreta: antes esta logica estaba metida dentro de un solo
 * extractor, con el parser, los encabezados, las fechas y los importes juntos.
 * Cuando se agrego el segundo lector de archivo, copiar esos metodos ahi era la unica
 * forma de tener fechas e importes en es-AR, y a las dos semanas los dos tenian un
 * parche distinto. Un bug de parseo se terminaba arreglando en un lado y el otro
 * seguia roto, sin que nada dijera cual de los dos era el bueno.
 *
 * Por eso lo que hay aca es SOLO la traduccion de texto a dato, y nada de I/O:
 *
 *  - `mapearColumnas` dice que columna es cual. Todos los bancos nombran las cosas
 *    distinto, asi que se aceptan los sinonimos de siempre.
 *  - `convertir` pasa una fila de strings a MovimientoBancoCrudo.
 *
 * Quien lo usa y como:
 *
 *  - Excel: el lector convierte cada celda a texto ANTES de llamar aca, y lo hace
 *    leyendo el VALOR, no el dibujo. Una celda numerica vale 152000.0 y se ve
 *    "152.000,00": si se le pidiera el texto con el formato de la celda, el numero
 *    dependeria del locale de la maquina que corre el servidor, y con eso un importe
 *    puede entrar como 152 o como 152000. Ver ExtractorExcelBanco.textoDe.
 *  - PDF: Tabula devuelve texto crudo y entra derecho por `convertir`. Ahi si hay
 *    que distinguir "1.234" de "1,23", y de eso se encarga `parseMonto`.
 */
final class FormatoExtracto {

    private FormatoExtracto() {}

    private static final DateTimeFormatter[] FECHAS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("yyyyMMdd")};

    /**
     * Normaliza un nombre de columna: sin tildes, sin espacios, sin guiones y en
     * minusculas. Asi "Fecha Operaci&#243;n", "FECHA OPERACION" y "fecha_operacion"
     * son la misma columna y no hacen falta tres casos en el mapeo.
     */
    static String normalizar(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[\\s_\\-.]", "");
    }

    /**
     * Mapea encabezado -&gt; indice de columna.
     *
     * La clave es SIEMPRE el nombre canonico ("fecha", "detalle", "importe",
     * "fecha_operacion", "es_credito", "comprobante", "saldo") que despues busca
     * `convertir`. Mapear "Fecha Operaci&#243;n" a la clave "Fecha Operaci&#243;n"
     * parece funcionar y no: el valor se busca con otro nombre y sale null.
     *
     * "fecha" gana si el archivo trae las dos parecidas (Fecha y Fecha Operacion con
     * espacios de sobra), porque la fecha valor es la que usa el filtro de pantalla.
     *
     * Si NO encuentra fecha o importe devuelve un mapa vacio. El llamador lo
     * interpreta como "este archivo no tiene encabezado" y usa el orden fijo de
     * columnas. Devolver el mapa a medias seria peor: importaria la mitad de las
     * columnas con nombres inventados.
     */
    static Map<String, Integer> mapearColumnas(List<String> encabezado) {
        Map<String, Integer> mapa = new HashMap<>();
        for (int i = 0; i < encabezado.size(); i++) {
            String n = normalizar(encabezado.get(i));
            if (n.isEmpty()) {
                continue;
            }
            if (n.equals("fecha") && !mapa.containsKey("fecha")) {
                mapa.put("fecha", i);
            } else if ((n.equals("fechaoperacion") || n.equals("fechaefectiva")
                    || n.equals("fechavalor") || n.equals("fechacontable"))
                    && !mapa.containsKey("fecha_operacion")) {
                mapa.put("fecha_operacion", i);
            } else if ((n.equals("detalle") || n.equals("concepto") || n.equals("descripcion")
                    || n.equals("glosa") || n.equals("movimiento") || n.equals("referencia"))
                    && !mapa.containsKey("detalle")) {
                mapa.put("detalle", i);
            } else if ((n.equals("importe") || n.equals("monto") || n.equals("importears")
                    || n.equals("valor") || n.equals("debe") || n.equals("haber"))
                    && !mapa.containsKey("importe")) {
                mapa.put("importe", i);
            } else if ((n.equals("escredito") || n.equals("signo") || n.equals("tipo")
                    || n.equals("sentido") || n.equals("dc"))
                    && !mapa.containsKey("es_credito")) {
                mapa.put("es_credito", i);
            } else if ((n.equals("comprobante") || n.equals("id") || n.equals("idtransaccion")
                    || n.equals("numerodemovimiento") || n.equals("numero") || n.equals("nro"))
                    && !mapa.containsKey("comprobante")) {
                mapa.put("comprobante", i);
            } else if ((n.equals("saldo") || n.equals("saldoactual")) && !mapa.containsKey("saldo")) {
                mapa.put("saldo", i);
            }
        }
        if (!mapa.containsKey("fecha") || !mapa.containsKey("importe")) {
            return new HashMap<>();
        }
        return mapa;
    }

    /** El orden de columnas cuando el archivo NO trae encabezado. */
    static Map<String, Integer> columnasPorPosicion() {
        Map<String, Integer> cols = new HashMap<>();
        cols.put("fecha", 0);
        cols.put("detalle", 1);
        cols.put("importe", 2);
        cols.put("es_credito", 3);
        cols.put("comprobante", 4);
        cols.put("saldo", 5);
        return cols;
    }

    /**
     * Fila de strings -&gt; movimiento. Devuelve null si la fila no tiene fecha o el
     * importe no se puede leer, y el llamador la saltea.
     *
     * Un importe ilegible NO es motivo para abortar el archivo entero: un extracto
     * de 900 filas con una fila de subtotal al final es el caso normal, no el
     * excepcional.
     */
    static MovimientoBancoCrudo convertir(List<String> fila, Map<String, Integer> cols) {
        LocalDate fecha = parseFecha(valor(fila, cols, "fecha"));
        if (fecha == null) {
            return null;
        }
        String detalle = valor(fila, cols, "detalle");
        if (detalle == null || detalle.isBlank()) {
            detalle = "MOVIMIENTO SIN DETALLE";
        }

        BigDecimal bruto = parseMonto(valor(fila, cols, "importe"));
        if (bruto == null) {
            return null;
        }

        boolean esCredito;
        String columnaSigno = valor(fila, cols, "es_credito");
        if (columnaSigno != null && !columnaSigno.isBlank()) {
            String s = columnaSigno.trim().toUpperCase(Locale.ROOT);
            // "C"/"CREDITO"/"INGRESO"/"HABER" = entra plata. El resto (D, DEBITO,
            // PAGO, SALIDA) es debito. Un banco que escribe "+"/"-" cae en el
            // else y el signo del importe define el lado, que es lo correcto.
            esCredito = s.startsWith("C") || s.startsWith("H") || s.startsWith("I") || s.startsWith("+");
        } else {
            esCredito = bruto.signum() >= 0;
        }

        LocalDate operacion = parseFecha(valor(fila, cols, "fecha_operacion"));
        String comprobante = valor(fila, cols, "comprobante");
        BigDecimal saldo = parseMonto(valor(fila, cols, "saldo"));

        return new MovimientoBancoCrudo(fecha, operacion, detalle,
                bruto.abs(), esCredito, comprobante, saldo);
    }

    static String valor(List<String> fila, Map<String, Integer> cols, String nombre) {
        Integer i = cols.get(nombre);
        if (i == null || i >= fila.size()) {
            return null;
        }
        String v = fila.get(i);
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    /** Null si no reconoce el formato. Ninguna exception: se prueban todos. */
    static LocalDate parseFecha(String s) {
        if (s == null) {
            return null;
        }
        String limpio = s.trim();
        if (limpio.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter f : FECHAS) {
            try {
                return LocalDate.parse(limpio, f);
            } catch (RuntimeException ignorada) {
                // siguiente formato
            }
        }
        return null;
    }

    /**
     * Importe en formato argentino, que es como los bancos Argentineos escriben
     * siempre: "152.000,00", "-48.000,00", "$ 45.000,50", "(1.234,56)".
     *
     * El caso que hace CONFUNDIR a cualquier parser es "1.234": puede ser mil
     * doscientos treinta y cuatro o uno con doscientos treinta y cuatro. Mirando
     * el numero no hay forma de saberlo, asi que se aplica una regla unica y
     * verificable: un separador seguido de EXACTAMENTE tres digitos es separador de
     * miles, cualquier otra cantidad de digitos es decimal. O sea 1.234 = 1234 y
     * 1.23 = 1.23, igual para coma y para punto.
     *
     * Consecuencia honesta de esa regla: un importe real de 1,234 pesos (con tres
     * decimales, que un banco no emite) se leeria como 1234. No se pierde nada
     * real, porque los extractos bancarios vienen con dos decimales.
     *
     * El parentesis de los balances contables (que restan) tambien se acepta.
     *
     * Null si no hay numero, para que el llamador salte la fila en vez de
     * meter un cero que parece un movimiento de importe cero.
     */
    static BigDecimal parseMonto(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        boolean negativoPorParentesis = t.startsWith("(") && t.endsWith(")");
        if (negativoPorParentesis) {
            t = t.substring(1, t.length() - 1);
        }
        // Todo lo que no es digito, coma, punto o menos, es ruido: "$", "%", "USD",
        // letras de cuenta, espacios de miles.
        t = t.replaceAll("[^0-9,.-]", "");
        if (t.isEmpty() || t.equals("-") || t.equals(".") || t.equals(",")) {
            return null;
        }
        // "1.234.567,89" -> miles con punto, decimal con coma.
        // "1,234,567.89" -> miles con coma, decimal con punto (formato US).
        if (t.contains(",") && t.contains(".")) {
            if (t.lastIndexOf(',') > t.lastIndexOf('.')) {
                t = t.replace(".", "").replace(',', '.');
            } else {
                t = t.replace(",", "");
            }
        } else if (t.contains(",")) {
            // Solo coma. Misma regla de tres digitos = miles, que con el punto.
            int ultima = t.lastIndexOf(',');
            if (t.substring(ultima + 1).length() == 3) {
                t = t.replace(",", "");
            } else {
                t = t.replace(',', '.');
            }
        } else if (t.contains(".")) {
            // Solo punto: "1.234" (miles) contra "1.23" (decimal).
            int ultima = t.lastIndexOf('.');
            if (t.substring(ultima + 1).length() == 3) {
                t = t.replace(".", "");
            }
        }
        try {
            BigDecimal v = new BigDecimal(t);
            return negativoPorParentesis ? v.negate() : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
