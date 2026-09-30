package com.conciliacion.infrastructure.banco;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reconstruye la tabla de un PDF por la POSICION de cada glifo, sin Tabula.
 *
 * â”€â”€ POR QUE NO ALCANZA CON TABULA â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 *
 * Tabula deduce la grilla de dos maneras y las dos fallan con estos extractos:
 *
 *  - POR LINEAS DIBUJADAS ("lattice"): estos PDF no dibujan ninguna. Devuelve 0.
 *  - POR ESPACIOS ("spreadsheet"): mira la posicion horizontal y parte la pagina en
 *    fragmentos. Con el extracto de Santander de julio 2026 devuelve 24 "tablas"
 *    de UNA fila cada una, con las celdas vacias, y el encabezado
 *    `Fecha | Comprobante | Movimiento | Caja de Ahorro en pesos` separado del
 *    resto. Con el de Galicia, 0 tablas. No es que no sepa leerlos: es que
 *    parte la pagina de una forma que no es una tabla.
 *
 * â”€â”€ QUE HACE ESTA CLASE, EN ORDEN â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 *
 *  1. Junta cada glifo del PDF con su coordenada. Un PDF no tiene palabras: tiene
 *     letras sueltas con una posicion. Santander dibuja "Adelii" con cinco glifos
 *     de ancho cero, y por eso PDFBox lo devuelve pegado.
 *
 *  2. Agrupa por Y para recuperar los RENGLONES. Tolerance de 2.5 puntos: en el
 *     Santander los renglones de datos estan a 22, 20, 22, 21 puntos uno del otro,
 *     y dentro de un renglon la linea base varia menos de 2.5. El margen entre
 *     ambos es enorme, asi que el umbral no es critico.
 *
 *  3. Parte cada renglon en CELDAS por el hueco horizontal, y aqui esta el dato
 *     que hace que esto funcione. En la pagina 2 del Santander los huecos que hay
 *     entre glifos son:
 *
 *          0,0 pts   x2072   los glifos de una misma palabra, ancho cero
 *        1,5-2,2 pts            el espacio DENTRO de una frase
 *       11-13 pts   x12       el espacio ENTRE columnas
 *       21-26 pts   x2
 *       36-42 pts   x20       el salto de la columna de importe a la de saldo
 *      164-202 pts            del concepto al importe
 *
 *     Entre 2,2 y 11 no hay NADA. O sea que hay una franja vacia y cualquier
 *     umbral en ese medio parte bien la pagina. Se usa 8.
 *
 *     Y el importe se parte bien aunque su X cambie: el banco lo alinea a la
 *     derecha, entonces "-$ 76.142,15" empieza en 443 y "$ 6.417,58" en 367. Por
 *     eso se parte por HUECO y no por coordenada de columna.
 *
 * Lo que sale de aca es una `List<List<String>>`, exactamente la misma forma que
 * produce el lector de Excel, y de ahi en adelante la usa `FormatoExtracto` sin
 * cambiar una linea. Ese es el motivo de que esa clase exista.
 *
 * â”€â”€ LO QUE ESTA CLASE NO HACE â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 *
 * No adivina que celda es la fecha ni cual es el importe: de eso se encarga
 * `FormatoExtracto`, que ya lo sabe hacer. Esta clase solo arma la grilla.
 */
final class TablaPorPosiciones {

    private TablaPorPosiciones() {}

    /** A partir de esto, hay un espacio entre palabras. Ver la tabla de huecos. */
    private static final double UMBRAL_PALABRA = 0.6;

    /**
     * Corte de columna cuando TODAVIA NO HAY GRILLA, o sea en el paso previo a
     * armarla. Es el mismo valor que se midio en el Santander (el hueco entre
     * columnas arranca en 11 puntos y el de palabras se queda en 2,2), pero con
     * grilla ya no se usa: el corte sale de la grilla, no de un numero redondo. Ver
     * `celdasDeRenglon`.
     */
    private static final double UMBRAL_COLUMNA = 8.0;

    /** Dos glifos con la linea base a menos de esto son del mismo renglon. */
    private static final double TOLERANCIA_FILA = 2.5;

    /**
     * ── COMO SE ARMA LA GRILLA: PERFIL DE TINTA, NO CORTES DE RENGLON ──────────
     *
     * La primera version armo la grilla con los huecos de cada renglon, y se
     * rompio. En la pagina 2 del Santander:
     *
     *     encabezado:  [Fecha][Comprobante][Movimiento][Caja de Ahorro...][Saldo]
     *     renglon 1:   [26/06/26][Adelii iibb tucuman letra i][-$ 89,10][$ 133.501,36]
     *
     * La celda "Comprobante" del renglon 1 esta VACIA, y como no hay nada que
     * partir, todo se corre a la izquierda: el importe cae en el indice de
     * "Movimiento" y el saldo en el de "Comprobante". En el renglon 3, donde el
     * comprobante SI esta, el importe cae en el indice correcto. O sea que la misma
     * columna seria dos columnas distintas segun el renglon.
     *
     * La segunda version junto los cortes de todos los renglones, y tambien fallo,
     * por un motivo mas sutil: el importe esta alineado a la DERECHA, asi que
     * "-$ 89,10" empieza en 376 y "-$ 76.142,15" en 443. Con cortes finos los dos
     * caian en columnas distintas. Y el encabezado del Santander tiene DOS
     * etiquetas de cuenta, "Caja de Ahorro en pesos" y "Cuenta Corriente en pesos",
     * que ponian un corte en 417, justo en medio de la columna del importe.
     *
     * La grilla correcta sale de la TINTA DE TODA LA PAGINA, no de los huecos de
     * una fila. Se marca cada punto del ancho de la pagina donde hay alguna letra,
     * en TODOS los renglones de movimiento a la vez, y las bandas de puntos que
     * quedan VACIAS son las separaciones entre columnas. En la pagina 2 del
     * Santander, las bandas vacias son:
     *
     *        0 - 23 pts    margen de la pagina
     *       60 - 65 pts    entre Fecha y Comprobante
     *      110 - 115 pts   entre Comprobante y Movimiento
     *      300 - 337 pts   entre Movimiento y el importe
     *      490 - 528 pts   entre el importe y el Saldo
     *
     * Cinco columnas, y los importes caen TODOS en la misma, esten donde esten.
     *
     * Y por que esto no se rompe con los textos de distinta longitud: un concepto
     * corto como "Comision por servicio de cuenta" deja vacio de 236 a 337, pero el
     * renglon de al lado tiene un concepto mas largo que ocupa ese espacio. Como la
     * proyeccion mira todos los renglones a la vez, ahi hay tinta y no se abre una
     * columna falsa. Un metodo que mire una fila sola no puede saber eso.
     */
    static List<List<String>> filasDe(PDDocument doc, int indicePagina) {
        List<List<String>> filas = new ArrayList<>();
        if (indicePagina < 0 || indicePagina >= doc.getNumberOfPages()) {
            return filas;
        }
        List<List<TextPosition>> renglones = renglonesDe(doc, indicePagina);
        if (renglones.isEmpty()) {
            return filas;
        }

        double[] cortes = cortesDeTinta(renglones);
        int columnas = cortes.length + 1;

        for (List<TextPosition> renglon : renglones) {
            List<String> celdas = celdasDe(renglon, cortes, columnas);
            boolean todaVacia = celdas.stream().allMatch(c -> c == null || c.isBlank());
            if (todaVacia) {
                continue;
            }
            filas.add(celdas);
        }
        return filas;
    }

    /** Separaciones verticales entre columnas, en puntos desde el borde izquierdo. */
    private static final double BANDA_MINIMA = 3.0;

    /**
     * Las bandas verticales sin tinta de los renglones de movimiento, y su centro.
     *
     * Solo se miran los renglones que TIENEN una fecha o un importe. Si se tomara la
     * pagina entera, el membrete de arriba (el nombre del titular, el CBU, la
     * direccion) abriria bandas falsas donde la tabla no tiene separacion, y la
     * grilla saldria mas gruesa que la tabla real.
     */
    private static double[] cortesDeTinta(List<List<TextPosition>> renglones) {
        List<List<TextPosition>> deMovimiento = new ArrayList<>();
        for (List<TextPosition> renglon : renglones) {
            if (pareceMovimiento(renglon)) {
                deMovimiento.add(renglon);
            }
        }
        if (deMovimiento.isEmpty()) {
            deMovimiento = renglones;
        }

        double maxX = 0;
        for (List<TextPosition> renglon : deMovimiento) {
            for (TextPosition glifo : renglon) {
                maxX = Math.max(maxX, glifo.getXDirAdj() + glifo.getWidth());
            }
        }
        int fondo = (int) Math.ceil(maxX) + 1;
        if (fondo <= 0) {
            return new double[0];
        }

        boolean[] conTinta = new boolean[fondo];
        for (List<TextPosition> renglon : deMovimiento) {
            for (TextPosition glifo : renglon) {
                int desde = Math.max(0, (int) Math.floor(glifo.getXDirAdj()));
                int hasta = Math.min(fondo - 1, (int) Math.ceil(glifo.getXDirAdj() + glifo.getWidth()));
                for (int x = desde; x <= hasta; x++) {
                    conTinta[x] = true;
                }
            }
        }

        List<Double> cortes = new ArrayList<>();
        int inicio = -1;
        for (int x = 0; x < fondo; x++) {
            if (!conTinta[x]) {
                if (inicio < 0) {
                    inicio = x;
                }
            } else if (inicio >= 0) {
                agregarCorte(cortes, inicio, x - 1, fondo);
                inicio = -1;
            }
        }
        if (inicio >= 0) {
            agregarCorte(cortes, inicio, fondo - 1, fondo);
        }

        double[] salida = new double[cortes.size()];
        for (int i = 0; i < salida.length; i++) {
            salida[i] = cortes.get(i);
        }
        return salida;
    }

    /**
     * El medio de la banda, salvo que sea el margen de la pagina.
     *
     * La banda del margen izquierdo arranca en 0 y la de la derecha termina en el
     * final del ancho, y esas no son separaciones entre columnas: son el borde del
     * papel. Si se tomaran, la tabla empezaria con una columna vacia y la primera
     * fecha caeria en la segunda.
     */
    private static void agregarCorte(List<Double> cortes, int desde, int hasta, int fondo) {
        if (hasta - desde + 1 < BANDA_MINIMA) {
            return;
        }
        if (desde == 0 || hasta == fondo - 1) {
            return;
        }
        cortes.add((desde + hasta) / 2.0);
    }

    /**
     * El renglon tiene al menos una celda que parece fecha o al menos una que parece
     * monto.
     *
     * Ojo con el orden: aca se parte SIN grilla, y recien despues se arma la grilla.
     * Si se hiciera al reves, al calcular la grilla todavia no se tiene, todas las
     * celdas cairian en la columna 0 y quedaria una sola cadena por renglon.
     * "26/06/26 Adelii iibb tucuman -$ 89,10 $ 133.501,36" no es ni una fecha ni un
     * monto, asi que ningun renglon de la tabla pasaria el filtro y la grilla
     * saldria vacia. Ya paso: con ese error la grilla salia de 0 cortes y cada fila
     * del Santander llegaba al extractor como un solo texto.
     */
    private static boolean pareceMovimiento(List<TextPosition> renglon) {
        for (Celda celda : celdasDeRenglon(renglon, SIN_CORTES)) {
            if (FormatoExtracto.parseFecha(celda.texto()) != null) {
                return true;
            }
            if (FormatoExtracto.parseMonto(celda.texto()) != null) {
                return true;
            }
        }
        return false;
    }

    /** Sin grilla, que es lo que hay antes de armarla. */
    private static final double[] SIN_CORTES = new double[0];

    /** Un trozo de texto con la franja horizontal que ocupa. */
    private record Celda(double inicio, double fin, String texto) {}

    /** Recorta la pagina a las filas que parecen una tabla de movimientos. */
    private static List<List<TextPosition>> renglonesDe(PDDocument doc, int indicePagina) {
        List<TextPosition> todos = glifosDe(doc, indicePagina);

        todos.sort(Comparator.comparingDouble((TextPosition t) -> t.getYDirAdj())
                .thenComparingDouble(TextPosition::getXDirAdj));

        List<List<TextPosition>> renglones = new ArrayList<>();
        List<TextPosition> actual = new ArrayList<>();
        double yActual = Double.NaN;

        for (TextPosition glifo : todos) {
            double y = glifo.getYDirAdj();
            if (!actual.isEmpty() && Math.abs(y - yActual) > TOLERANCIA_FILA) {
                renglones.add(actual);
                actual = new ArrayList<>();
            }
            actual.add(glifo);
            yActual = y;
        }
        if (!actual.isEmpty()) {
            renglones.add(actual);
        }
        return renglones;
    }

    /** Todos los glifos de la pagina, con la posicion que cada uno tiene. */
    private static List<TextPosition> glifosDe(PDDocument doc, int indicePagina) {
        List<TextPosition> todos = new ArrayList<>();
        try {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String texto, List<TextPosition> posiciones)
                        throws IOException {
                    todos.addAll(posiciones);
                    super.writeString(texto, posiciones);
                }
            };
            // PDFBox numera las paginas de 1 y este metodo recibe el indice de 0.
            stripper.setStartPage(indicePagina + 1);
            stripper.setEndPage(indicePagina + 1);
            stripper.getText(doc);
        } catch (IOException e) {
            // Una pagina que PDFBox no puede leer no es motivo para abortar el
            // archivo entero: las otras paginas pueden venir bien.
            return todos;
        }
        return todos;
    }

    /**
     * Un renglon -&gt; sus celdas, colocadas en la grilla de la pagina.
     *
     * Cada celda se ubica en la columna con la que MAS se superpone, no en la que
     * arranca. Es la diferencia entre que el importe entre bien o no: el banco
     * alinea los importes a la derecha, y "-$ 76.142,15" empieza en 443 mientras
     * que "$ 6.417,58" de la fila de al lado empieza en 367. Con el corte en 370,
     * el primero caeria en la columna del concepto y el segundo no. Superponiendo,
     * los dos caen en la del importe, porque los dos la cubren mas.
     */
    private static List<String> celdasDe(List<TextPosition> renglon, double[] cortes, int columnas) {
        List<String> grilla = new ArrayList<>(columnas);
        for (int i = 0; i < columnas; i++) {
            grilla.add(null);
        }
        for (Celda celda : celdasDeRenglon(renglon, cortes)) {
            ubicar(grilla, cortes, celda.inicio(), celda.fin(), celda.texto());
        }
        return grilla;
    }

    /**
     * Un renglon -&gt; sus celdas, partiendo por la grilla y no por un hueco fijo.
     *
     * ── POR QUE NO BASTA PARTIR POR HUECO ─────────────────────────────────────
     *
     * Con un unico umbral (8 puntos) el corte depende de la fila, y en el Santander
     * eso rompe: en la pagina 3 la fecha "02/07/26" termina en 58 y el
     * comprobante "1819111" arranca en 62, o sea que el hueco es de 4 puntos y los
     * dos valores quedaban pegados en la misma celda. En la pagina 2 el hueco entre
     * los mismos dos campos es de 7 y se separaban. O sea que el mismo datoCaia en
     * dos columnas segun la pagina, sin que nada en el archivo lo explique.
     *
     * La grilla no tiene ese problema: es la misma para todos los renglones de la
     * pagina, y el hueco pequeno se usa solo para saber donde TERMINA una palabra,
     * que es el unico uso que hace bien.
     *
     * El corte exige las dos cosas: que el glifo caiga en otra columna de la grilla
     * Y que haya un hueco. Con las dos, un concepto largo que cruza un corte no se
     * parte al pedazo, porque dentro de una palabra el hueco es cero.
     */
    private static List<Celda> celdasDeRenglon(List<TextPosition> renglon, double[] cortes) {
        List<Celda> salida = new ArrayList<>();
        List<TextPosition> ordenados = new ArrayList<>(renglon);
        ordenados.sort(Comparator.comparingDouble(TextPosition::getXDirAdj));

        double inicioCelda = Double.NaN;
        double finCelda = Double.NaN;
        StringBuilder texto = new StringBuilder();
        double finGlifoAnterior = Double.NaN;
        int columnaActual = -1;

        for (TextPosition glifo : ordenados) {
            String letra = glifo.getUnicode();
            if (letra == null || letra.isEmpty()) {
                continue;
            }
            boolean arrancaCelda = Double.isNaN(inicioCelda);
            if (!arrancaCelda) {
                double hueco = glifo.getXDirAdj() - finGlifoAnterior;
                int columna = columnaDe(cortes, glifo.getXDirAdj());
                boolean cambiaDeColumna = columna != columnaActual && hueco > UMBRAL_PALABRA;
                // Sin grilla no hay columnas con las que comparar, asi que el corte
                // tiene que salir del hueco. Con grilla manda la grilla, porque sola
                // hacia que la fecha y el comprobante quedaran pegados o separados
                // segun la pagina.
                boolean sinGrilla = cortes.length == 0 && hueco >= UMBRAL_COLUMNA;
                if (cambiaDeColumna || sinGrilla) {
                    salida.add(new Celda(inicioCelda, finCelda, texto.toString()));
                    texto.setLength(0);
                    inicioCelda = Double.NaN;
                    finCelda = Double.NaN;
                } else if (hueco > UMBRAL_PALABRA) {
                    texto.append(' ');
                }
            }
            if (Double.isNaN(inicioCelda)) {
                inicioCelda = glifo.getXDirAdj();
                columnaActual = columnaDe(cortes, glifo.getXDirAdj());
            }
            finCelda = glifo.getXDirAdj() + glifo.getWidth();
            texto.append(letra);
            finGlifoAnterior = finCelda;
        }
        if (texto.length() > 0) {
            salida.add(new Celda(inicioCelda, finCelda, texto.toString()));
        }
        return salida;
    }

    /** La columna de la grilla que contiene esta coordenada, contando desde 0. */
    private static int columnaDe(double[] cortes, double x) {
        int columna = 0;
        for (int c = 0; c < cortes.length; c++) {
            if (cortes[c] < x) {
                columna = c + 1;
            } else {
                break;
            }
        }
        return columna;
    }

    /**
     * Un renglon -&gt; sus celdas, partiendo SOLO por los huecos.
     *
     * No usa la grilla, asi que sirve para decidir si el renglon es de movimiento,
     * que es lo que se necesita antes de tener la grilla. Para armar las filas se
     * usa `celdasDeRenglon`, que ademas usa la grilla.
     */
    private static List<Celda> celdasPorHueco(List<TextPosition> renglon) {
        return celdasDeRenglon(renglon, new double[0]);
    }

    /** Escribe el texto en la columna de la grilla con la que mas se superpone. */
    private static void ubicar(List<String> grilla, double[] cortes,
                               double inicio, double fin, String texto) {
        int mejor = 0;
        double mejorSuperposicion = -1;
        for (int c = 0; c < cortes.length; c++) {
            double desde = cortes[c];
            double hasta = c + 1 < cortes.length ? cortes[c + 1] : Double.MAX_VALUE;
            double superposicion = Math.min(fin, hasta) - Math.max(inicio, desde);
            if (superposicion > mejorSuperposicion) {
                mejorSuperposicion = superposicion;
                mejor = c + 1;
            }
        }
        // Dos celdas pueden caer en la misma columna si el renglon las escribio pegado.
        // Se suman con un espacio en vez de pisar la primera.
        String previa = grilla.get(mejor);
        grilla.set(mejor, previa == null || previa.isEmpty() ? texto : previa + " " + texto);
    }
}
