package com.conciliacion.infrastructure.banco;

import com.conciliacion.application.banco.ExtractorBancario;
import com.conciliacion.application.banco.MovimientoBancoCrudo;
import com.conciliacion.application.banco.RangoFechas;
import com.conciliacion.domain.model.CuentaBancaria;
import com.conciliacion.domain.model.OrigenMovimiento;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.PageIterator;
import technology.tabula.Rectangle;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.detectors.NurminenDetectionAlgorithm;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lee el PDF del banco. Es el formato mas comun de los home banking y el mas hostil:
 * un PDF es una pagina dibujada, no una tabla, asi que hay que adivinar donde estan
 * las celdas antes de poder leerlas.
 *
 * ── COMO SE RESUELVE ──────────────────────────────────────────────────────────
 *
 * Tabula hace el trabajo de verdad, pero por detras es PDFBox: saca los caracteres
 * con su posicion en la pagina, y despues usa esas coordenadas para decidir que
 * forma una grilla. Se usa `SpreadsheetExtractionAlgorithm`, que esta pensada para
 * tablas tipo planilla, que es justo como es un extracto.
 *
 * ── EL LIMITE REAL, Y HAY QUE DECIRLO ─────────────────────────────────────────
 *
 * Tabula solo ve PDF con CAPA DE TEXTO. Si el banco manda el extracto escaneado
 * (una imagen de una pagina), no hay caracteres que leer: la pagina entra vacia y
 * el extractor devuelve cero filas.
 *
 * Ese caso NO es un error del usuario ni un bug: es un formato que esta tecnologia
 * no cubre. Por eso la excepcion que se tira dice explicitamente "escanado" y
 * proposes la alternativa, en vez de devolver un cero mudo que hace pensar que el
 * extracto no tenia movimientos. Cubrirlo de verdad requiere OCR (Tesseract o
 * similar), que es otra dependencia y otro orden de magnitud; queda fuera.
 *
 * ── LO QUE NO HACE ───────────────────────────────────────────────────────────
 *
 *  - No inventa comprobante. Los PDF de banco no traen ID de transaccion, asi que
 *    `comprobante` queda null y esta fuente NO deduplica: si se sube dos veces el
 *    mismo PDF, las filas se duplican. Es el limite real de trabajar con archivos y
 *    la razon por la que las APIs son preferibles. Con el UNIQUE de
 *    (cuenta, comprobante) no se rompe nada por tener null, pero tampoco protege.
 *  - No lee PDF cifrados ni protegidos con clave. Son distintos y hay que decirlo.
 */
@Component
public class ExtractorPdfBanco implements ExtractorBancario {

    @Override
    public String codigo() { return "PDF"; }

    @Override
    public String descripcion() { return "Extracto en PDF del banco (con capa de texto)"; }

    @Override
    public OrigenMovimiento origen() { return OrigenMovimiento.PDF; }

    @Override
    public boolean aceptaArchivo() { return true; }

    @Override
    public boolean puedeEjecutarseSolo() { return false; }

    @Override
    public List<String> extensionesAceptadas() { return List.of(".pdf"); }

    @Override
    public List<MovimientoBancoCrudo> extraer(CuentaBancaria cuenta, RangoFechas rango) {
        throw new UnsupportedOperationException(
                "PDF necesita un archivo: usar POST /importaciones/archivo");
    }

    @Override
    public List<MovimientoBancoCrudo> extraerDeArchivo(CuentaBancaria cuenta, RangoFechas rango,
                                                        byte[] contenido) {
        List<MovimientoBancoCrudo> salida = new ArrayList<>();
        int paginas = 0;
        int tablasVistas = 0;

        try (PDDocument doc = PDDocument.load(contenido)) {
            ObjectExtractor extractor = new ObjectExtractor(doc);
            PageIterator paginasIter = extractor.extract();

            while (paginasIter.hasNext()) {
                Page pagina = paginasIter.next();
                paginas++;
                List<MovimientoBancoCrudo> deEstaPagina =
                        extraerDePagina(doc, pagina, paginas - 1, rango);
                tablasVistas += deEstaPagina.isEmpty() ? 0 : 1;
                salida.addAll(deEstaPagina);
            }
        } catch (IOException e) {
            throw new NoSePudoAbrirException(
                    "No se pudo abrir el PDF. Puede estar cifrado o protegido con clave, "
                            + "que este lector no soporta. Detalle: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new NoSePudoAbrirException(
                    "El PDF esta danado o tiene una estructura que no se puede leer. "
                            + "Detalle: " + e.getMessage());
        }

        if (salida.isEmpty()) {
            // El caso del PDF escaneado. Decirlo es la diferencia entre que el
            // usuario sepa que tiene que otra herramienta y que piense que le
            // importamos un extracto vacio.
            throw new PdfSinCapaDeTextoException(paginas, tablasVistas);
        }
        return salida;
    }

    /**
     * Saca la tabla de UNA pagina. Y aca esta lo que hace que este lector funcione o
     * no, asi que va escrito paso a paso y no como un detalle:
     *
     * Un PDF no es una tabla: es una pagina dibujada. Tabula puede deducir donde
     * estan las celdas de dos maneras, y cada una sirve para un tipo de PDF:
     *
     *  1. POR LINEAS DIBUJADAS ("lattice"). El extractor ve los rectangulos y las
     *     lineas que el banco imprimo alrededor de cada celda y arma la grilla con
     *     eso. Es la mas precisa, y devuelve la fila tal cual esta.
     *
     *  2. POR LOS ESPACIOS EN BLANCO ("stream"). No hay ninguna linea: la tabla se
     *     deduce de las columnas de texto alineadas, del hueco que queda entre una
     *     columna y la siguiente.
     *
     * Y EL ORDEN IMPORTA. Con el metodo 1 solo, un PDF sin grilla devuelve CERO
     * tablas y el extractor dice "no se encontro ningun movimiento" sobre un
     * extracto perfectamente legible. Que es lo que pasaba: el PDF de prueba tiene
     * cero rulings y el algoritmo 1 sobre el daba 0 tablas, mientras el 2 habia
     * leido las cuatro filas enteras, con los importos y los detalles sin partir.
     *
     * Por eso se prueba el 1 y, SOLO si no de nada, el 2. En ese orden, y no
     * juntando los resultados de los dos: si se juntaran, una tabla que los dos
     * algoritmos encuentran se contaria dos veces y cada movimiento se importaria
     * duplicado. Con el fallback, el segundo metodo corre solo cuando el primero
     * fallo, que es justo cuando no hay nada que duplicar.
     */
    private List<MovimientoBancoCrudo> extraerDePagina(PDDocument doc, Page pagina,
                                                       int indicePagina, RangoFechas rango) {
        List<MovimientoBancoCrudo> porPosicion = extraerDePaginaPorPosiciones(doc, indicePagina, rango);
        if (!porPosicion.isEmpty()) {
            return porPosicion;
        }

        List<MovimientoBancoCrudo> conLineas = new ArrayList<>();
        for (Table tabla : new SpreadsheetExtractionAlgorithm().extract(pagina)) {
            conLineas.addAll(convertirTabla(tabla, rango));
        }
        if (!conLineas.isEmpty()) {
            return conLineas;
        }

        List<MovimientoBancoCrudo> sinLineas = new ArrayList<>();
        NurminenDetectionAlgorithm detector = new NurminenDetectionAlgorithm();
        BasicExtractionAlgorithm lector = new BasicExtractionAlgorithm();
        for (Rectangle area : detector.detect(pagina)) {
            for (Table tabla : lector.extract(pagina.getArea(area))) {
                sinLineas.addAll(convertirTabla(tabla, rango));
            }
        }
        return sinLineas;
    }

    /**
     * Camino principal: armar la grilla por la posicion de los glifos.
     *
     * Va PRIMERO, antes que Tabula, y no por gusto. Con los cinco extractos
     * bancarios reales que hay para probar (Santander, Galicia, ICBC, BBVA, y el
     * consolidado), Tabula no encuentra la tabla en ninguno: con el Santander
     * devuelve 24 fragmentos de una fila con las celdas vacias, y con el Galicia y
     * el ICBC devuelve 0 tablas. No es que los PDF sean ilegibles: es que la
     * pagina esta dibujada con las columnas alineadas por ESPACIO y Tabula las
     * corta distinto. Ver TablaPorPosiciones.
     *
     * Tabula queda como respaldo, no se borra: hay PDFs con grilla dibujada, que es
     * justo lo que Tabula le mejor.
     */
    private List<MovimientoBancoCrudo> extraerDePaginaPorPosiciones(PDDocument doc, int indicePagina,
                                                                    RangoFechas rango) {
        return convertirFilas(TablaPorPosiciones.filasDe(doc, indicePagina), rango);
    }

    /**
     * Filas de celdas -&gt; movimientos.
     *
     * El encabezado se busca por nombre entre las primeras 10 filas y, si asi no
     * sale, se completa por contenido. Hace falta el segundo intento porque el
     * Santander encabezado la columna del dinero como "Caja de Ahorro en pesos", que
     * es el nombre de la cuenta: por nombre no se reconoce, pero sus celdas si son
     * importes, y de ahi se saca. Ver FormatoExtracto.completarPorContenido.
     */
    private List<MovimientoBancoCrudo> convertirFilas(List<List<String>> filas, RangoFechas rango) {
        if (filas.isEmpty()) {
            return List.of();
        }
        Encabezado encabezado = buscarEncabezadoEnFilas(filas);
        if (encabezado == null) {
            return List.of();
        }

        List<MovimientoBancoCrudo> salida = new ArrayList<>();
        for (int f = encabezado.desdeFila(); f < filas.size(); f++) {
            List<String> celdas = filas.get(f);
            if (celdas.stream().allMatch(c -> c == null || c.isBlank())) {
                continue;
            }
            try {
                MovimientoBancoCrudo crudo = FormatoExtracto.convertir(celdas, encabezado.columnas());
                if (crudo != null && enRango(crudo, rango)) {
                    salida.add(crudo);
                }
            } catch (RuntimeException ignorada) {
                // Igual que en convertirTabla: una fila rota no tira el archivo.
            }
        }
        return salida;
    }

    /**
     * Busca la fila que hace de encabezado entre las primeras 10, probando primero
     * por nombre y despues completando por contenido.
     *
     * El limite de 10 filas sale de medirlo: en los cuatro bancos el encabezado esta
     * en la fila 0, 2 o 3, contando desde el primer renglon con texto de la pagina.
     */
    private Encabezado buscarEncabezadoEnFilas(List<List<String>> filas) {
        int limite = Math.min(filas.size(), 10);
        for (int f = 0; f < limite; f++) {
            Map<String, Integer> porNombre = FormatoExtracto.mapearPorNombre(filas.get(f));
            if (porNombre.isEmpty()) {
                continue;
            }
            // Con nombre alcanza: se respeta el mapa tal cual, sin tocar nada.
            Map<String, Integer> completo = FormatoExtracto.completarPorContenido(porNombre, filas);
            if (!completo.isEmpty()) {
                return new Encabezado(completo, f + 1);
            }
        }
        return null;
    }

    private List<MovimientoBancoCrudo> convertirTabla(Table tabla, RangoFechas rango) {
        List<List<RectangularTextContainer>> filas = tabla.getRows();
        if (filas.isEmpty()) {
            return List.of();
        }
        Encabezado encabezado = buscarEncabezado(filas);
        if (encabezado == null) {
            return List.of();
        }

        List<MovimientoBancoCrudo> salida = new ArrayList<>();
        for (int f = encabezado.desdeFila(); f < filas.size(); f++) {
            List<String> celdas = textos(filas.get(f));
            if (celdas.stream().allMatch(c -> c == null || c.isBlank())) {
                // Fila de separacion entre secciones, no un movimiento roto.
                continue;
            }
            try {
                MovimientoBancoCrudo crudo = FormatoExtracto.convertir(celdas, encabezado.columnas());
                if (crudo != null && enRango(crudo, rango)) {
                    salida.add(crudo);
                }
            } catch (RuntimeException ignorada) {
                // Ver FormatoExtracto.convertir: una fila ilegible se saltea, no tira
                // abajo el archivo entero. Un extracto de 900 filas con una fila de
                // subtotal al final es el caso normal, no la excepcion.
            }
        }
        return salida;
    }

    /**
     * Busca la fila que hace de encabezado y de donde arrancan los datos.
     *
     * No puede ser la primera fila y punto: los PDF de banco arrancan con el nombre
     * del banco, el numero de cuenta y un par de renglones en blanco antes de la
     * tabla. Si seTomara la fila 0 como encabezado, el archivo entero se descartaria
     * y el usuario veria "no se encontro ningun movimiento" con un PDF perfectamente
     * legible.
     *
     * Por eso se prueban las primeras 10 filas: alcanza para los encabezados
     *reasonably cerca del inicio, y si el titulo esta partido en cinco lineas el
     * archivo no se pierde igual.
     *
     * Devuelve null si ninguna fila tiene fecha e importe, que en una tabla de este
     * PDF quiere decir que no es la tabla de movimientos (puede ser un resumen de
     * saldos o un pie de firma) y hay que seguir con la siguiente.
     */
    private Encabezado buscarEncabezado(List<List<RectangularTextContainer>> filas) {
        int limite = Math.min(filas.size(), 10);
        for (int f = 0; f < limite; f++) {
            Map<String, Integer> intento = FormatoExtracto.mapearColumnas(textos(filas.get(f)));
            if (!intento.isEmpty()) {
                return new Encabezado(intento, f + 1);
            }
        }
        return null;
    }

    /** El mapa de columnas y la primera fila de datos. */
    private record Encabezado(Map<String, Integer> columnas, int desdeFila) {}

    private boolean enRango(MovimientoBancoCrudo crudo, RangoFechas rango) {
        if (rango.desde() != null && crudo.fecha().isBefore(rango.desde())) {
            return false;
        }
        return rango.hasta() == null || !crudo.fecha().isAfter(rango.hasta());
    }

    /**
     * Una fila de la tabla a lista de strings.
     *
     * `getText()` sin argumentos mete un salto de linea entre palabras de la misma
     * celda, y eso parte "TRANSFERENCIA RECIBIDA" en dos strings. Con `false` sale
     * la celda corrida, que es lo que un humans lee.
     *
     * Ojo con el indice: las filas de Tabula pueden tener menos celdas que las
     * columnas que el encabezado declaro. Por eso se completa con vacios hasta
     * llegar, en vez de confiar en que los indices calcen.
     */
    private List<String> textos(List<RectangularTextContainer> fila) {
        List<String> salida = new ArrayList<>(fila.size());
        for (RectangularTextContainer celda : fila) {
            String t = celda.getText(false);
            salida.add(t == null ? "" : t.trim());
        }
        return salida;
    }

    /**
     * No se encontro ninguna fila utilizable. 422 con un mensaje que dice la causa
     * real, porque "0 leidos" no le dice a nadie que hacer.
     */
    public static class PdfSinCapaDeTextoException extends RuntimeException {
        public PdfSinCapaDeTextoException(int paginas, int tablasVistas) {
            super("No se encontro ningun movimiento en el PDF. Lo mas probable es que sea "
                    + "un PDF escaneado (una imagen) y no tenga texto: se necesita OCR para "
                    + "leerlo, y este lector no hace OCR. Si el extracto lo tenes tambien en "
                    + "Excel o CSV, subilo por esa via. (paginas: " + paginas
                    + ", tablas detectadas: " + tablasVistas + ")");
        }
    }

    /** El archivo se rompio y no se pudo ni abrir. 422: no es culpa del servidor. */
    public static class NoSePudoAbrirException extends RuntimeException {
        public NoSePudoAbrirException(String m) { super(m); }
    }
}
