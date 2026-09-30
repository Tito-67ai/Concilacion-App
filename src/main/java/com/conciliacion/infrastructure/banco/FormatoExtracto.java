package com.conciliacion.infrastructure.banco;

import com.conciliacion.application.banco.MovimientoBancoCrudo;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    /**
     * Formatos de fecha que se aceptan, en el orden en que se prueban.
     *
     * Los dos ultimos son los de AÑO DE DOS DIGITOS, y estan porque el Santander los
     * usa: el extracto de julio 2026 viene con "26/06/26" y "02/07/26". Sin ellos,
     * `parseFecha` devolvia null en TODAS las filas del Santander y el archivo
     * entero se descartaba como si no tuviera texto, cuando el problema era que la
     * fecha estava a dos digitos.
     *
     * "yy" se resuelve contra la base 2000, asi que 26 es 2026. No hay ambiguedad con
     * los formatos de cuatro digitos porque "yy" exige exactamente dos.
     */
    private static final DateTimeFormatter[] FECHAS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("dd/MM/yy"),
            DateTimeFormatter.ofPattern("d/M/yy")};

    /**
     * Normaliza un nombre de columna: sin tildes, sin espacios, sin guiones, sin
     * puntos y sin el signo de pesos, y en minusculas. Asi "Fecha Operaci&#243;n",
     * "FECHA OPERACION" y "fecha_operacion" son la misma columna y no hacen falta
     * tres casos en el mapeo.
     *
     * El `$` se saca porque los export de banco Argentineos ponen la moneda pegada
     * al nombre: "Debito en $" y "Credito en $". Sin sacarlo, esas dos columnas
     * normalizan a "debitoen$" y "creditoen$", que no matchean nada, y el archivo
     * entero se descarta por un signo de moneda.
     */
    static String normalizar(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[\\s_\\-.$]", "");
    }

    /**
     * Sufijos que los bancos le pegan al nombre de la columna de dinero: "Debito",
     * "Debito en $", "Debito ARS", "Debito USD".
     *
     * Se comparan contra una lista cerrada y no con un `startsWith`, porque
     * `startsWith("credito")` tambien se agarra a un "Credito disponible" o a un
     * "Credito vigente" y los mapearia a la columna de ingresos, tirando a la
     * basura filas de un estado de cuenta. Con la lista, "creditodisponible" no
     * matchea y la columna queda sin mapear, que es lo correcto.
     */
    private static final String[] SUFIJOS_MONTO = {
            "", "en", "ars", "usd", "mtc", "dolar", "dolares", "pesos",
            "enars", "enusd", "enmtc", "endolar"};

    /** El encabezado es la columna de ese lado del dinero, con o sin moneda pegada. */
    private static boolean esColumnaDeMonto(String n, String nombre) {
        for (String sufijo : SUFIJOS_MONTO) {
            if (n.equals(nombre + sufijo)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mapea encabezado -&gt; indice de columna.
     *
     * La clave es SIEMPRE el nombre canonico ("fecha", "detalle", "contraparte",
     * "importe", "debe", "haber", "fecha_operacion", "es_credito", "comprobante",
     * "saldo") que despues busca `convertir`. Mapear "Fecha Operaci&#243;n" a la
     * clave "Fecha Operaci&#243;n" parece funcionar y no: el valor se busca con otro
     * nombre y sale null.
     *
     * ── DEBE Y HABER SON COLUMNAS DISTINTAS, NO SINONIMOS DE IMPORTE ────────────
     *
     * Este es el punto que mas filas ha costado. Un extracto bancario trae el
     * dinero partido en dos columnas, "Debito en $" y "Credito en $", y una sola
     * de las dos viene con numero en cada fila: un pago tiene debito y el credito
     * en blanco, un cobro al reves.
     *
     * Si "debe" y "haber" se aceptan como sinonimos de la MISMA clave "importe", pasa
     * lo que pasaba: gana la primera que aparece y la otra se tira. Cada fila con
     * el dinero en la columna descartada llega al parser con un null, se la saltea,
     * y la MITAD del extracto entra como si no existiera. Peor: las que si entran
     * vienen de la columna "Debe", cuyos numeros son positivos, asi que sin columna
     * de signo el parser las clasifica de credito y un debito de 32 millones entra
     * con signo mas.
     *
     * Con "debe" y "haber" como claves propias, el LADO lo dice la columna que
     * tiene el numero, no el signo del numero, y las dos mitades entran.
     *
     * ── "fecha contable" ES LA FECHA, NO LA FECHA OPERACION ──────────────────────
     *
     * Galicia y los demas exportan "Fecha contable" y ningun otro campo de fecha.
     * Manda a "fecha_operacion" sola, sin "fecha" al lado, el encabezado entero se
     * rechaza por el `if` del final y el archivo se procesa con CERO movimientos.
     * Ahora va a "fecha", que es la que usa el filtro de pantalla, y solo cae en
     * "fecha_operacion" si el archivo ya trajo una "fecha" propia.
     *
     * ── "contraparte": EL NOMBRE DEL TERCERO ────────────────────────────────────
     *
     * El export de Galicia separa "Concepto" (que dice QUE paso) de "Nombre" (que
     * dice A QUIEN), y el nombre esta diez columnas mas a la derecha. Leyendo solo
     * el concepto, la pantalla muestra "TRANSF CONNBKG" y la persona no tiene idea
     * de a quien se le pago: para conciliar, el nombre es la mitad del dato. Se
     * guarda aparte y `convertir` junta las dos columnas.
     *
     * Si NO encuentra fecha ni ningun importe devuelve un mapa vacio. El llamador
     * lo interpreta como "este archivo no tiene encabezado" y usa el orden fijo de
     * columnas. Devolver el mapa a medias seria peor: importaria la mitad de las
     * columnas con nombres inventados.
     */
    static Map<String, Integer> mapearColumnas(List<String> encabezado) {
        return soloSiSirve(mapearPorNombre(encabezado));
    }

    /**
     * El mapa aunque le falte fecha o importe, para que `completarPorContenido` lo
     * pueda terminar. `mapearColumnas` es este metodo con la puerta de adentro.
     */
    static Map<String, Integer> mapearPorNombre(List<String> encabezado) {
        Map<String, Integer> mapa = new HashMap<>();
        for (int i = 0; i < encabezado.size(); i++) {
            String n = normalizar(encabezado.get(i));
            if (n.isEmpty()) {
                continue;
            }
            if (n.equals("fecha") && !mapa.containsKey("fecha")) {
                mapa.put("fecha", i);
            } else if (n.equals("fechacontable")) {
                // Ver la nota de arriba: es la fecha, salvo que el archivo ya tenga
                // una columna "fecha" propia, en cuyo caso esta pasa a ser la
                // secundaria en vez de pisarla.
                if (!mapa.containsKey("fecha")) {
                    mapa.put("fecha", i);
                } else if (!mapa.containsKey("fecha_operacion")) {
                    mapa.put("fecha_operacion", i);
                }
            } else if ((n.equals("fechaoperacion") || n.equals("fechaefectiva")
                    || n.equals("fechavalor"))
                    && !mapa.containsKey("fecha_operacion")) {
                mapa.put("fecha_operacion", i);
            } else if ((n.equals("detalle") || n.equals("concepto") || n.equals("descripcion")
                    || n.equals("glosa") || n.equals("movimiento") || n.equals("referencia"))
                    && !mapa.containsKey("detalle")) {
                mapa.put("detalle", i);
            } else if ((n.equals("nombre") || n.equals("nombretercero")
                    || n.equals("nombreyapellido") || n.equals("apellidoynombre")
                    || n.equals("tercero") || n.equals("razonsocial")
                    || n.equals("beneficiario") || n.equals("ordenante")
                    || n.equals("titular") || n.equals("acreedor"))
                    && !mapa.containsKey("contraparte")) {
                mapa.put("contraparte", i);
            } else if ((n.equals("debe") || esColumnaDeMonto(n, "debito"))
                    && !mapa.containsKey("debe")) {
                mapa.put("debe", i);
            } else if ((n.equals("haber") || esColumnaDeMonto(n, "credito"))
                    && !mapa.containsKey("haber")) {
                mapa.put("haber", i);
            } else if ((n.equals("importe") || n.equals("monto") || n.equals("importears")
                    || n.equals("valor"))
                    && !mapa.containsKey("importe")) {
                mapa.put("importe", i);
            } else if ((n.equals("escredito") || n.equals("signo") || n.equals("tipo")
                    || n.equals("sentido") || n.equals("dc"))
                    && !mapa.containsKey("es_credito")) {
                mapa.put("es_credito", i);
            } else if ((n.equals("comprobante") || n.equals("id") || n.equals("idtransaccion")
                    || n.equals("numerodemovimiento") || n.equals("numero") || n.equals("nro"))
                    && !mapa.containsKey("comprobante")) {
                // OJO: aca NO se mapea "nrodoc". En el export de Galicia, "Nro doc"
                // no es un id de movimiento sino el CUIT del tercero, que se repite
                // en todas las filas de una misma contraparte. Mapearlo como
                // comprobante haria que el UNIQUE (cuenta, comprobante) descarte
                // movimientos validos distintos que comparten CUIT: peor que no
                // deduplicar, que solo duplica.
                    mapa.put("comprobante", i);
            } else if ((n.startsWith("saldo")) && !mapa.containsKey("saldo")) {
                // Prefijo y no igualdad a proposito: "Saldo en $", "Saldo actual" y
                // "Saldo anterior" son las tres el mismo campo. Y acá el riesgo de
                // agarrar la columna de mas es nulo: el saldo no se usa para
                // deduplicar ni para conciliar, solo se muestra.
                mapa.put("saldo", i);
            }
        }
        return mapa;
    }

    /** El mapa sirve si tiene fecha y alguna forma de importe. Si no, va vacio. */
    private static Map<String, Integer> soloSiSirve(Map<String, Integer> mapa) {
        boolean sinImporte = !mapa.containsKey("importe")
                && !mapa.containsKey("debe") && !mapa.containsKey("haber");
        if (!mapa.containsKey("fecha") || sinImporte) {
            return new HashMap<>();
        }
        return mapa;
    }

    /**
     * Segundo intento: cuando el encabezado NO nombra la columna del dinero, la
     * busca por lo que las celdas contienen.
     *
     * ── POR QUE HACE FALTA ─────────────────────────────────────────────────────
     *
     * El mapeo por nombre solo reconoce lo que se llama "importe", "monto", "debe",
     * "haber" o "valor". Y ningun banco Argentino le pone esos nombres a la columna
     * del movimiento. Lo que se trouver en el Santander de julio 2026:
     *
     *     Fecha | Comprobante | Movimiento | Caja de Ahorro en pesos | Saldo en cuenta
     *
     * "Caja de Ahorro en pesos" es el nombre de la cuenta, no del campo, asi que el
     * mapa por nombre no encuentra importe, la puerta de `soloSiSirve` lo rechaza,
     * y la pagina entera se pierde. Lo mismo con el BBVA, que la llama "MAN$", y
     * con el Galicia, que la llama "Saldo en $".
     *
     * ── COMO SE ELIGE, Y POR QUE NO ES ADIVINAR ────────────────────────────────
     *
     * La columna del dinero es la que RESPONDE como dinero: casi todas sus celdas
     * parsean con `parseMonto`. Eso no lo dice el encabezado, lo dicen los datos, y
     * es un dato del archivo, no una suposicion sobre el banco. Tres reglas para
     * que no se cuele ninguna columna mas:
     *
     *  1. Solo columnas que ningun nombre mapeo. En el Santander la columna 1 se
     *     llama "Comprobante" y sus valores son "28781698", que tambien parsean
     *     como numero. Si se la considerara, el sistema leeria el numero de
     *     comprobante como si fuera el importe.
     *  2. Al menos 60% de las celdas con dato tienen que parsear como importe, y
     *     tiene que haber al menos 3. Si no hay volumen, no se decide nada.
     *  3. La mitad de los importes tienen que traer coma, punto o signo $: una
     *     columna de puros enteros es un numero de cheque o una cantidad, no
     *     plata. Entre las que pasan, gana la de MAS A LA IZQUIERDA, que en un
     *     extracto es el movimiento y no el saldo de la cuenta.
     *
     * ── Y SI HAY DOS COLUMNAS DE DINERO ────────────────────────────────────────
     *
     * El Santander de julio 2026 tiene dos, y no es una rareza del dibujo: el
     * encabezado dice "Caja de Ahorro en pesos" y "Cuenta Corriente en pesos", dos
     * columnas de la misma tabla, y cada movimiento va en una de las dos. Medido en
     * la pagina 2, los importes terminan en x=408 los unos y en x=492 los otros:
     *
     *     [-$ 89,10]      376-408      [-$ 76.142,15]  443-492
     *     [$ 38.505,48]   362-408      [-$ 15.989,85]  443-492
     *
     * O sea que la columna logica del importe queda partida en dos franjas. No hay
     * ningun criterio puramente geometrico que las una: el hueco entre 408 y 443 es
     * de 35 puntos, y el hueco entre el importe y el saldo es de 36. Son
     * indistinguibles salvo por saber que las dos son plata.
     *
     * La segunda columna se mapea a `importe2` y se lee si `importe` viene vacio.
     * Solo se acepta una segunda si NO hay ninguna fila con las dos columnas
     * llenadas: si las dos tuvieran dato a la vez, serian dos campos distintos
     * (un debe y un haber, por ejemplo) y ahi manda el encabezado, no la geometria.
     */
    static Map<String, Integer> completarPorContenido(Map<String, Integer> porNombre,
                                                       List<List<String>> filas) {
        Map<String, Integer> mapa = new HashMap<>(porNombre);

        if (!tieneImporte(mapa)) {
            List<Integer> columnas = columnasDeDinero(filas, mapa);
            if (!columnas.isEmpty()) {
                mapa.put("importe", columnas.get(0));
                if (columnas.size() > 1) {
                    mapa.put("importe2", columnas.get(1));
                }
            }
        }
        if (!mapa.containsKey("fecha")) {
            Integer columna = columnaDeFechas(filas, mapa);
            if (columna != null) {
                mapa.put("fecha", columna);
            }
        }
        return soloSiSirve(mapa);
    }

    private static boolean tieneImporte(Map<String, Integer> mapa) {
        return mapa.containsKey("importe") || mapa.containsKey("importe2")
                || mapa.containsKey("debe") || mapa.containsKey("haber");
    }

    /** Cuantas filas mira para decidir. Alcanza con 20 y evita recorrer un PDF de 900. */
    private static final int MUESTRA = 20;

    private static final double MINIMO_APROBADO = 0.6;
    private static final double MINIMO_CON_SEPARADOR = 0.5;
    private static final int MINIMO_CELDAS = 3;

    /**
     * Las columnas que se comportan como columna de dinero, de izquierda a derecha.
     *
     * Devuelve como mucho dos. Con mas, la tabla no es de movimientos-bankaria sino
     * algo que no se debe leer a ojo en este lector.
     */
    private static List<Integer> columnasDeDinero(List<List<String>> filas, Map<String, Integer> ocupados) {
        int ancho = anchoDe(filas);
        int[] conDato = new int[ancho];
        int[] conNumero = new int[ancho];
        int[] conSeparador = new int[ancho];

        for (int c = 0; c < ancho; c++) {
            if (ocupados.containsValue(c)) {
                continue;
            }
            for (String celda : celdasDeLaColumna(filas, c)) {
                conDato[c]++;
                if (parseMonto(celda) == null) {
                    continue;
                }
                conNumero[c]++;
                if (tieneSeparador(celda)) {
                    conSeparador[c]++;
                }
            }
        }

        List<Integer> candidatos = new ArrayList<>();
        for (int c = 0; c < ancho; c++) {
            if (conDato[c] < MINIMO_CELDAS) {
                continue;
            }
            if ((double) conNumero[c] / conDato[c] < MINIMO_APROBADO) {
                continue;
            }
            if ((double) conSeparador[c] / conDato[c] < MINIMO_CON_SEPARADOR) {
                continue;
            }
            candidatos.add(c);
        }

        List<Integer> salida = new ArrayList<>();
        for (Integer c : candidatos) {
            if (!salida.isEmpty() && hayFilaConLasDosColumnas(filas, salida.get(0), c)) {
                break;
            }
            salida.add(c);
            if (salida.size() == 2) {
                break;
            }
        }
        return salida;
    }

    /** Algun renglon tiene dato en las dos columnas a la vez. */
    private static boolean hayFilaConLasDosColumnas(List<List<String>> filas, int una, int otra) {
        for (List<String> fila : filas) {
            boolean enUna = una < fila.size() && fila.get(una) != null && !fila.get(una).isBlank();
            boolean enOtra = otra < fila.size() && fila.get(otra) != null && !fila.get(otra).isBlank();
            if (enUna && enOtra) {
                return true;
            }
        }
        return false;
    }

    /** La columna mas a la izquierda que se comporta como columna de fechas. */
    private static Integer columnaDeFechas(List<List<String>> filas, Map<String, Integer> ocupados) {
        int ancho = anchoDe(filas);
        for (int c = 0; c < ancho; c++) {
            if (ocupados.containsValue(c)) {
                continue;
            }
            int conDato = 0;
            int conFecha = 0;
            for (String celda : celdasDeLaColumna(filas, c)) {
                conDato++;
                if (parseFecha(celda) != null) {
                    conFecha++;
                }
            }
            if (conDato < MINIMO_CELDAS) {
                continue;
            }
            if ((double) conFecha / conDato < MINIMO_APROBADO) {
                return null;
            }
            return c;
        }
        return null;
    }

    /** Un monto de verdad trae coma, punto o el signo de pesos. "28781698" no. */
    private static boolean tieneSeparador(String celda) {
        return celda.indexOf(',') >= 0 || celda.indexOf('.') >= 0 || celda.indexOf('$') >= 0
                || celda.indexOf('-') >= 0;
    }

    private static int anchoDe(List<List<String>> filas) {
        int ancho = 0;
        for (List<String> fila : filas) {
            ancho = Math.max(ancho, fila.size());
        }
        return ancho;
    }

    /** Las celdas con dato de una columna, hasta MUESTRA. */
    private static List<String> celdasDeLaColumna(List<List<String>> filas, int columna) {
        List<String> salida = new ArrayList<>();
        for (List<String> fila : filas) {
            if (salida.size() >= MUESTRA) {
                break;
            }
            String v = columna < fila.size() ? fila.get(columna) : null;
            if (v == null || v.isBlank()) {
                continue;
            }
            salida.add(v.trim());
        }
        return salida;
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
     *
     * ── EL LADO LO DICE LA COLUMNA, NO EL SIGNO DEL NUMERO ──────────────────────
     *
     * Este metodo resolvia el lado con `bruto.signum() >= 0` cuando no habia columna
     * de signo. Con un extracto de dos columnas (Debe y Haber) eso esta mal: los
     * numeros de la columna "Debe" son positivos, asi que todos los debitos entaban
     * como creditos. En un extracto real de 134 filas quedaban las 19 de la otra
     * hoja, las 19 con signo mas, y todas debitos.
     *
     * Ahora, si el archivo trae "debe" o "haber", el lado sale de QUE COLUMNA tiene
     * el numero, y el signo del numero se usa solo para el absoluto. Si el archivo
     * trae una sola columna "importe", se sigue mirando el signo o la columna de
     * signo, que es como funcionaba antes.
     */
    static MovimientoBancoCrudo convertir(List<String> fila, Map<String, Integer> cols) {
        LocalDate fecha = parseFecha(valor(fila, cols, "fecha"));
        if (fecha == null) {
            return null;
        }

        BigDecimal delDebe = parseMonto(valor(fila, cols, "debe"));
        BigDecimal delHaber = parseMonto(valor(fila, cols, "haber"));
        BigDecimal delImporte = parseMonto(valor(fila, cols, "importe"));
        if (delImporte == null) {
            // Extractos que traen dos cuentas en la misma tabla (Santander: Caja de
            // Ahorro y Cuenta Corriente). Cada movimiento va en una de las dos, nunca
            // en las dos, asi que se usa la que tenga el numero. Ver
            // completarPorContenido.
            delImporte = parseMonto(valor(fila, cols, "importe2"));
        }

        BigDecimal bruto;
        boolean esCredito;
        if (delDebe != null || delHaber != null) {
            if (delDebe != null && delHaber != null) {
                // Las dos columnas con numero en la misma fila es un caso raro. Se
                // queda con la que no sea cero, que es la unica que trae un
                // movimiento de verdad; si las dos son cero, se trata como debito.
                if (delHaber.signum() != 0 && delDebe.signum() == 0) {
                    bruto = delHaber;
                    esCredito = true;
                } else {
                    bruto = delDebe;
                    esCredito = false;
                }
            } else if (delDebe != null) {
                bruto = delDebe;
                esCredito = false;
            } else {
                bruto = delHaber;
                esCredito = true;
            }
        } else {
            bruto = delImporte;
            if (bruto == null) {
                return null;
            }
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
        }

        String detalle = juntarDescripcion(valor(fila, cols, "detalle"),
                valor(fila, cols, "contraparte"));

        LocalDate operacion = parseFecha(valor(fila, cols, "fecha_operacion"));
        String comprobante = valor(fila, cols, "comprobante");
        BigDecimal saldo = parseMonto(valor(fila, cols, "saldo"));

        return new MovimientoBancoCrudo(fecha, operacion, detalle,
                bruto.abs(), esCredito, comprobante, saldo);
    }

    private static final String SIN_DETALLE = "MOVIMIENTO SIN DETALLE";

    /**
     * Junta el concepto con el nombre del tercero: "TRANSF CONNBKG" +
     * "LA ESTRELLA SOCIEDAD ANONIMA" -&gt; "TRANSF CONNBKG - LA ESTRELLA SOCIEDAD ANONIMA".
     *
     * Los export de banco Argentineos traen el concepto (QUE paso) y el nombre (A
     * QUIEN) en columnas separadas, con el nombre diez columnas mas a la derecha.
     * Leer solo el concepto deja la pantalla con "TRANSF CONNBKG", que no le dice
     * nada a nadie de a quien se le pago, y el nombre es la mitad del dato para
     * conciliar.
     *
     * Si el concepto ya viene con el nombre adentro, no se repite: algunos export
     * lo escriben en las dos columnas y duplicarlo daria "PEGO A X - X".
     */
    private static String juntarDescripcion(String concepto, String contraparte) {
        boolean vacioConcepto = concepto == null || concepto.isBlank();
        boolean vacioContraparte = contraparte == null || contraparte.isBlank();
        if (vacioConcepto && vacioContraparte) {
            return SIN_DETALLE;
        }
        if (vacioConcepto) {
            return contraparte;
        }
        if (vacioContraparte) {
            return concepto;
        }
        if (concepto.toUpperCase(Locale.ROOT).contains(contraparte.toUpperCase(Locale.ROOT))) {
            return concepto;
        }
        return concepto + " - " + contraparte;
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
