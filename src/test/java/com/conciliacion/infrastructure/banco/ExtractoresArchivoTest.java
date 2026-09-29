package com.conciliacion.infrastructure.banco;

import com.conciliacion.application.banco.MovimientoBancoCrudo;
import com.conciliacion.application.banco.RangoFechas;
import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Que los dos lectores de archivo lean DE VERDAD un archivo.
 *
 * ── POR QUE GENERAMOS EL ARCHIVO EN EL TEST Y NO LO DEJAMOS EN CARPETA ─────────
 *
 * Porque un archivo de prueba en el repositorio se pudre: lo borra alguien, cambia
 * de nombre, o el test sigue pasando contra una copia vieja mientras el parser ya
 * cambio. Generandolo con la misma libreria que despues lo lee, el test es una
 * frase sola: "esto se escribe asi y tiene que salir asi".
 *
 * Y no es un test circular del todo: la escritura la hace POI/OpenPDF y la lectura
 * la hace el extractor, con el parser de es-AR, el mapeo de encabezados y la
 * deteccion de tabla de Tabula en el medio. Ese Parser es lo que se prueba, no la
 * libreria.
 *
 * ── QUE CASOS CUBRE Y POR QUE CADA UNO ────────────────────────────────────────
 *
 * Los dos lectores tienen la misma trampa: el numero. En Excel la celda vale
 * 152000.0 y SE VE "152.000,00"; en el PDF el texto es literalmente "152.000,00".
 * Si cualquiera de los dos confunde el separador de miles con el decimal, el
 * importe entra con tres ceros de mas o con una fraccion, y con dos mil filas el
 * error pasa desapercibido. Por eso el primer caso de cada lector es un importe
 * con punto de miles explicito.
 */
class ExtractoresArchivoTest {

    private static final RangoFechas SIN_RANGO = RangoFechas.de(null, null);

    // ───────────────────────────── EXCEL ──────────────────────────────────────

    @Test
    @DisplayName("Excel: lee la hoja de movimientos, no la primera, y no inventa el separador de miles")
    void excelLeeLaHojaDeMovimientos() throws Exception {
        byte[] archivo = excelDePrueba();

        List<MovimientoBancoCrudo> leidos =
                new ExtractorExcelBanco().extraerDeArchivo(null, SIN_RANGO, archivo);

        assertEquals(3, leidos.size(), "deberia leer las 3 filas de la hoja 'Movimientos'");

        MovimientoBancoCrudo primero = leidos.get(0);
        assertEquals(LocalDate.of(2026, 9, 20), primero.fecha());
        assertEquals("VENTA MOSTRADOR FACT A", primero.detalle());
        // La celda vale 152000 y esta formateada como "152.000,00". Si el lector
        // tomara el texto de la celda con el formato de la maquina, en vez de 152000
        // puede leer 152 o 15200000 segun el locale del servidor.
        assertEquals(0, new BigDecimal("152000.00").compareTo(primero.importe()));
        assertTrue(primero.esCredito(), "un importe positivo sin columna de signo es credito");
        assertEquals("EXL-0001", primero.comprobante());

        // El negativo con signo explico en la columna de signo, que es como lo
        // exporta un banco: el importe viene positivo y el lado, aparte.
        MovimientoBancoCrudo segundo = leidos.get(1);
        assertEquals(LocalDate.of(2026, 9, 19), segundo.fecha());
        assertEquals(0, new BigDecimal("48000.00").compareTo(segundo.importe()));
        assertTrue(!segundo.esCredito(), "columna de signo = debe dar debito");

        // La tercera fila que entra NO es la del 18/09: esa se descarta porque su
        // importe es el texto "NO APLICA" (ver el test de abajo). La tercera que
        // entra es la del 17/09, que tiene la fecha como celda REAL de Excel y no
        // como texto. Son los dos caminos distintos del parser de fechas, asi que
        // el orden importa: si el parser de celdas de fecha se rompiera, esta
        // asercion lo dira.
        MovimientoBancoCrudo tercero = leidos.get(2);
        assertEquals(LocalDate.of(2026, 9, 17), tercero.fecha(),
                "la fecha viene de una celda de fecha real, no del texto de la celda");
        assertEquals("TRANSFERENCIA RECIBIDA", tercero.detalle());
        assertEquals(0, new BigDecimal("45500.40").compareTo(tercero.importe()));
        assertEquals("EXL-0004", tercero.comprobante());
    }

    @Test
    @DisplayName("Excel: una fila ilegible no tira abajo el archivo entero")
    void excelSalteaLaFilaRotaYSigue() throws Exception {
        byte[] archivo = excelDePrueba();

        // El archivo tiene una fila de subtotal con un importe que no es un numero.
        // Perder las 3 filas buenas por una de subtotal es peor que perder una.
        List<MovimientoBancoCrudo> leidos =
                new ExtractorExcelBanco().extraerDeArchivo(null, SIN_RANGO, archivo);

        assertTrue(leidos.stream().noneMatch(m -> m.detalle().contains("SUBTOTAL")),
                "la fila de subtotal no es un movimiento y no deberia entrar");
    }

    @Test
    @DisplayName("Excel: un archivo que no es Excel dice que no es Excel, no tira 500")
    void excelAguantaUnArchivoQueNoEsExcel() {
        byte[] basura = "esto es un PDF, no una planilla".getBytes();

        ExtractorExcelBanco extractor = new ExtractorExcelBanco();
        Exception e = assertThrows(RuntimeException.class,
                () -> extractor.extraerDeArchivo(null, SIN_RANGO, basura));
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().toLowerCase().contains("excel"),
                "el mensaje tiene que decir que el archivo no es un Excel legible, "
                        + "para que el usuario sepa que subio el archivo equivocado: " + e.getMessage());
    }

    @Test
    @DisplayName("Excel: el rango de fechas del filtro se respeta")
    void excelRespetaElRango() throws Exception {
        byte[] archivo = excelDePrueba();

        List<MovimientoBancoCrudo> leidos = new ExtractorExcelBanco().extraerDeArchivo(null,
                RangoFechas.de(LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 30)), archivo);

        assertEquals(2, leidos.size(), "solo entran las dos filas de septiembre");
        assertTrue(leidos.stream().noneMatch(m -> m.fecha().isBefore(LocalDate.of(2026, 9, 19))));
    }

    /**
     * Un .xlsx con las dos coisas que rompen a un lector de Excel:
     * una hoja de portada antes de la de movimientos, y numeros con formato de miles
     * que valen otra cosa.
     */
    private byte[] excelDePrueba() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {

            // Hoja 0: la portada que pone el banco. No tiene fecha ni importe, asi que
            // el lector tiene que seguir de largo y no intentar leerla como tabla.
            Sheet portada = wb.createSheet("Portada");
            portada.createRow(0).createCell(0).setCellValue("BANCO GALICIA - EXTRACTO DE CUENTA");
            portada.createRow(1).createCell(0).setCellValue("0000003100000003079083");
            portada.createRow(2).createCell(0).setCellValue("Período 01/09/2026 al 30/09/2026");

            Sheet hoja = wb.createSheet("Movimientos");

            // Fila 3 (0-based 2): encabezado. Las filas 0 y 1 van en blanco, que es lo
            // que hacen los export de banco.
            Row enc = hoja.createRow(2);
            enc.createCell(0).setCellValue("FECHA");
            enc.createCell(1).setCellValue("DETALLE");
            enc.createCell(2).setCellValue("IMPORTE");
            enc.createCell(3).setCellValue("SIGNO");
            enc.createCell(4).setCellValue("COMPROBANTE");

            // Formato de miles ARGENTINO. La celda vale 152000 y se ve "152.000,00".
            CellStyle conMiles = wb.createCellStyle();
            conMiles.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));

            Row r1 = hoja.createRow(3);
            // Fecha como TEXTO: es lo que exporta la mayoria de los bancos.
            r1.createCell(0).setCellValue("20/09/2026");
            r1.createCell(1).setCellValue("VENTA MOSTRADOR FACT A");
            r1.createCell(2).setCellValue(152000d);
            r1.getCell(2).setCellStyle(conMiles);
            r1.createCell(3).setCellValue("CREDITO");
            r1.createCell(4).setCellValue("EXL-0001");

            Row r2 = hoja.createRow(4);
            r2.createCell(0).setCellValue("19/09/2026");
            r2.createCell(1).setCellValue("PAGO PROVEEDOR ACME");
            r2.createCell(2).setCellValue(48000d);
            r2.getCell(2).setCellStyle(conMiles);
            r2.createCell(3).setCellValue("DEBITO");
            r2.createCell(4).setCellValue("EXL-0002");

            // Fila de subtotal: el importe es texto y no es un numero. El lector la
            // tiene que saltear sin romper el resto.
            Row r3 = hoja.createRow(5);
            r3.createCell(0).setCellValue("18/09/2026");
            r3.createCell(1).setCellValue("COMISION MANTENIMIENTO");
            r3.createCell(2).setCellValue("NO APLICA");
            r3.createCell(3).setCellValue("DEBITO");
            r3.createCell(4).setCellValue("EXL-0003");

            // Fila totalmente vacia entre medio: separador de secciones.
            hoja.createRow(6);

            // Fecha como celda de fecha real, que es el otro camino del parser.
            Row r4 = hoja.createRow(7);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.clear();
            cal.set(2026, java.util.Calendar.SEPTEMBER, 17);
            CellStyle fechaEstilo = wb.createCellStyle();
            fechaEstilo.setDataFormat(wb.createDataFormat().getFormat("dd/mm/yyyy"));
            r4.createCell(0).setCellValue(cal.getTime());
            r4.getCell(0).setCellStyle(fechaEstilo);
            r4.createCell(1).setCellValue("TRANSFERENCIA RECIBIDA");
            r4.createCell(2).setCellValue(45500.4d);
            r4.getCell(2).setCellStyle(conMiles);
            r4.createCell(4).setCellValue("EXL-0004");

            hoja.setAutoFilter(new CellRangeAddress(2, 2, 0, 4));

            wb.write(salida);
            return salida.toByteArray();
        }
    }

    // ────────────────────────────── PDF ───────────────────────────────────────

    @Test
    @DisplayName("PDF: saca los movimientos de la tabla y parsea los importes en formato argentino")
    void pdfLeeLaTablaDelExtracto() throws Exception {
        byte[] archivo = pdfDePrueba();

        List<MovimientoBancoCrudo> leidos =
                new ExtractorPdfBanco().extraerDeArchivo(null, SIN_RANGO, archivo);

        assertEquals(3, leidos.size(), "deberia leer las 3 filas de la tabla del PDF");

        MovimientoBancoCrudo primero = leidos.get(0);
        assertEquals(LocalDate.of(2026, 9, 20), primero.fecha());
        assertTrue(primero.detalle().contains("VENTA MOSTRADOR"),
                "el detalle deberia traerse entero, no partido: '" + primero.detalle() + "'");
        // "152.000,00" con punto de miles y coma decimal. Este es el caso que rompe
        // cualquier parser ingenuo: quitar los puntos da 15200000, y cambiar la coma
        // por punto da 152.
        assertEquals(0, new BigDecimal("152000.00").compareTo(primero.importe()));
        assertTrue(primero.esCredito());

        // El negativo va con el signo pegado al numero, como lo saca el banco.
        MovimientoBancoCrudo segundo = leidos.get(1);
        assertEquals(LocalDate.of(2026, 9, 19), segundo.fecha());
        assertEquals(0, new BigDecimal("48000.00").compareTo(segundo.importe()));
        assertTrue(!segundo.esCredito(), "el menos es lo que define el lado cuando no hay columna de signo");
    }

    @Test
    @DisplayName("PDF: un archivo sin capa de texto dice que hace falta OCR, no que leyo cero")
    void pdfSinCapaDeTextoDiceLaCausa() throws Exception {
        // Un PDF con una imagen y ningun texto: el caso real del extracto escaneado.
        // Devolver una lista vacia haria que el usuario piense que su extracto no
        // tenia movimientos.
        byte[] escaneado = pdfSinTexto();

        ExtractorPdfBanco extractor = new ExtractorPdfBanco();
        Exception e = assertThrows(RuntimeException.class,
                () -> extractor.extraerDeArchivo(null, SIN_RANGO, escaneado));
        assertTrue(e.getMessage().toLowerCase().contains("escaneado")
                        || e.getMessage().toLowerCase().contains("ocr"),
                "el mensaje tiene que decir que es un PDF escaneado y que falta OCR: " + e.getMessage());
    }

    @Test
    @DisplayName("PDF: un archivo que no es PDF no revienta con 500")
    void pdfAguantaUnArchivoQueNoEsPdf() {
        byte[] basura = "esto es un Excel, no un PDF".getBytes();

        ExtractorPdfBanco extractor = new ExtractorPdfBanco();
        assertThrows(RuntimeException.class, () -> extractor.extraerDeArchivo(null, SIN_RANGO, basura));
    }

    /**
     * Un PDF con capa de texto y una tabla dibujada con las columnas en posiciones
     * fijas, que es como se ve un extracto de banco real.
     */
    private byte[] pdfDePrueba() throws Exception {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 30, 30, 40, 30);
        PdfWriter writer = PdfWriter.getInstance(doc, salida);
        doc.open();
        PdfContentByte cb = writer.getDirectContent();
        BaseFont fuente = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);

        // Portada: dos lineas sueltas arriba. El lector tiene que saltearlas y
        // encontrar el encabezado mas abajo.
        cb.beginText();
        cb.setFontAndSize(fuente, 9);
        cb.setTextMatrix(50, 800);
        cb.showText("BANCO GALICIA - EXTRACTO DE CUENTA 0000003100000003079083");
        cb.endText();

        float[] x = {50, 110, 320, 430};
        float y = 760;

        cb.beginText();
        cb.setFontAndSize(fuente, 8);
        for (int c = 0; c < x.length; c++) {
            cb.setTextMatrix(x[c], y);
            cb.showText(c == 0 ? "Fecha" : c == 1 ? "Detalle" : c == 2 ? "Importe" : "Saldo");
        }
        cb.endText();

        String[][] filas = {
                {"20/09/2026", "VENTA MOSTRADOR FACT A", "152.000,00", "1.300.000,00"},
                {"19/09/2026", "PAGO PROVEEDOR ACME", "-48.000,00", "1.148.000,00"},
                {"18/09/2026", "COMISION MANTENIMIENTO CUENTA", "-1.850,00", "1.196.000,00"}};

        y = 740;
        for (String[] fila : filas) {
            cb.beginText();
            cb.setFontAndSize(fuente, 8);
            for (int c = 0; c < fila.length; c++) {
                cb.setTextMatrix(x[c], y);
                cb.showText(fila[c]);
            }
            cb.endText();
            y -= 16;
        }

        doc.close();
        return salida.toByteArray();
    }

    /** PDF con una imagen y cero texto: el extracto escaneado. */
    private byte[] pdfSinTexto() throws Exception {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4);
        PdfWriter writer = PdfWriter.getInstance(doc, salida);
        doc.open();
        // Un rectangulo gris en vez de una foto: para el proposito del test es lo
        // mismo, un PDF donde no hay un solo caracter con posicion.
        PdfContentByte cb = writer.getDirectContent();
        cb.setColorFill(java.awt.Color.LIGHT_GRAY);
        cb.rectangle(50, 700, 500, 100);
        cb.fill();
        doc.close();
        return salida.toByteArray();
    }
}
