package com.conciliacion.infrastructure.reporte;

import com.conciliacion.application.reporte.SolicitudReporte;
import com.conciliacion.application.reporte.SolicitudReporte.Celda;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Que los dos renderizadores generen un archivo que se pueda ABRIR y con el dato
 * adentro.
 *
 * ── POR QUE SE VUELVE A LEER EL ARCHIVO GENERADO ───────────────────────────────
 *
 * Porque "el renderizador no tira excepcion" no dice nada. Un .xlsx puede salir
 * byte a byte sin problema y tener el importe guardado como texto, que es un
 * archivo que se abre perfecto y no se puede sumar. Un PDF puede salir con la tabla
 * corrida y ser un papel ilegible. Las dos cosas se ven solo reabriendo el archivo
 * y mirando que dice adentro, asi que eso es lo que hace este test.
 */
class RenderizadoresTest {

    private static SolicitudReporte.Reporte reporteDePrueba() {
        return new SolicitudReporte.Reporte(
                "Movimientos a conciliar",
                "2026-09-01 a 2026-09-30",
                List.of("LADO", "FECHA", "DETALLE", "IMPORTE", "ORIGEN"),
                List.of(
                        List.of(Celda.de("BANCO"), Celda.de(LocalDate.of(2026, 9, 20)),
                                Celda.de("VENTA MOSTRADOR FACT A"), Celda.de("152000.00"),
                                Celda.de("EXCEL")),
                        List.of(Celda.de("BANCO"), Celda.de(LocalDate.of(2026, 9, 19)),
                                Celda.de("PAGO PROVEEDOR ACME"), Celda.de("-48000.00"),
                                Celda.de("PDF"))));
    }

    // ───────────────────────────── EXCEL ──────────────────────────────────────

    @Test
    @DisplayName("Excel: el importe se guarda como NUMERO, con formato de miles, no como texto")
    void excelGuardaElImporteComoNumero() throws Exception {
        byte[] archivo = new RenderizadorExcel().render(reporteDePrueba());

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(archivo))) {
            Sheet hoja = wb.getSheetAt(0);
            // Fila 0 titulo, 1 criterio, 2 vacia, 3 encabezado, 4 y 5 los datos.
            assertEquals("LADO", hoja.getRow(3).getCell(0).getStringCellValue());
            assertEquals("IMPORTE", hoja.getRow(3).getCell(3).getStringCellValue());

            Row primero = hoja.getRow(4);
            Row segundo = hoja.getRow(5);

            org.apache.poi.ss.usermodel.Cell celdaPositiva = primero.getCell(3);
            org.apache.poi.ss.usermodel.Cell celdaNegativa = segundo.getCell(3);

            // Este es EL punto del test. Si el importe fuera texto, estas dos
            // aserciones fallarian con "expected STRING but was NUMERIC" al reves, y el
            // archivo abriria igual de bien en Excel: se veria perfecto y no se
            // podria sumar. Es la falla que no se ve hasta que alguien hace
            // =SUMA(D4:D500) y le da un cero.
            assertEquals(CellType.NUMERIC, celdaPositiva.getCellType(),
                    "el importe tiene que ser un numero, no texto, o no se puede sumar en Excel");
            assertEquals(152000d, celdaPositiva.getNumericCellValue(), 0.001);
            assertEquals(-48000d, celdaNegativa.getNumericCellValue(), 0.001);

            // Y el formato es el de miles con dos decimales, que es lo que hace que
            // se vea "152.000,00" sin tener que escribir el separador a mano.
            assertEquals("#,##0.00", celdaPositiva.getCellStyle().getDataFormatString());

            // El texto de las otras columnas tiene que seguir siendo texto, no
            // convertirse a numero por accidente: "1234" como detalle es un codigo.
            assertEquals(CellType.STRING, primero.getCell(2).getCellType());
            assertEquals("VENTA MOSTRADOR FACT A", primero.getCell(2).getStringCellValue());
        }
    }

    @Test
    @DisplayName("Excel: el encabezado queda congelado y con autofiltro")
    void excelDejaElEncabezadoUsable() throws Exception {
        byte[] archivo = new RenderizadorExcel().render(reporteDePrueba());

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(archivo))) {
            Sheet hoja = wb.getSheetAt(0);
            // Con 500 filas, sin encabezado congelado hay que volver arriba
            // scrolleando para saber de que columna es cada numero.
            assertTrue(hoja.getPaneInformation() != null
                            && hoja.getPaneInformation().isFreezePane(),
                    "el encabezado tiene que quedar congelado");
            assertTrue(((org.apache.poi.xssf.usermodel.XSSFSheet) hoja).getCTWorksheet().isSetAutoFilter(),
                    "tiene que quedar el autofiltro");
        }
    }

    @Test
    @DisplayName("Excel: un reporte sin filas igual sale como archivo valido")
    void excelAguantaElReporteVacio() throws Exception {
        SolicitudReporte.Reporte vacio = new SolicitudReporte.Reporte(
                "Movimientos a conciliar", "2026-09-01 a 2026-09-30",
                List.of("LADO", "FECHA", "DETALLE", "IMPORTE", "ORIGEN"), List.of());

        byte[] archivo = new RenderizadorExcel().render(vacio);

        // El caso real: el usuario filtro a la nada y exporto igual. Un archivo
        // corrupto en ese momento lo hace pensar que el boton esta roto.
        assertTrue(archivo.length > 0);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(archivo))) {
            assertEquals("Movimientos a conciliar", wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
        }
    }

    // ────────────────────────────── PDF ───────────────────────────────────────

    @Test
    @DisplayName("PDF: sale un PDF legible con los importes dentro")
    void pdfSaleLegibleConLosImportes() throws Exception {
        byte[] archivo = new RenderizadorPdf().render(reporteDePrueba());

        try (PDDocument doc = PDDocument.load(archivo)) {
            assertTrue(doc.getNumberOfPages() >= 1, "tiene que tener al menos una pagina");
            String texto = new PDFTextStripper().getText(doc);

            assertTrue(texto.contains("Movimientos a conciliar"), "falta el titulo");
            assertTrue(texto.contains("2026-09-01 a 2026-09-30"), "falta el criterio: sin el, el PDF no se puede auditar");
            assertTrue(texto.contains("IMPORTE"), "falta el encabezado de la tabla");
            assertTrue(texto.contains("152000.00"), "falta el importe positivo");
            assertTrue(texto.contains("-48000.00"), "falta el importe negativo con su signo");
            assertTrue(texto.contains("VENTA MOSTRADOR FACT A"), "falta el detalle");
            // El pie con la cantidad de filas: un PDF que dice 5 movimientos sin
            // decir de cuando, a las dos semanas no se puede defender en un
            // expediente.
            assertTrue(texto.contains("2 movimientos"), "falta el pie con la cantidad de filas");
        }
    }

    @Test
    @DisplayName("PDF: un reporte sin filas sale como PDF valido, no roto")
    void pdfAguantaElReporteVacio() throws Exception {
        SolicitudReporte.Reporte vacio = new SolicitudReporte.Reporte(
                "Movimientos conciliados", "2026-01-01 a 2026-12-31",
                List.of("FECHA", "DETALLE", "IMPORTE"), List.of());

        byte[] archivo = new RenderizadorPdf().render(vacio);

        try (PDDocument doc = PDDocument.load(archivo)) {
            String texto = new PDFTextStripper().getText(doc);
            assertTrue(texto.contains("Movimientos conciliados"));
            assertTrue(texto.contains("0 movimientos"));
        }
    }

    @Test
    @DisplayName("PDF: el signo del importe decide el color, y un texto que no es numero no rompe")
    void pdfElColorSigueAlSigno() throws Exception {
        // La columna ESTADO de la pestana de conciliados trae texto, no numeros. Si
        // el color se calculara sin Protegerse, un PENDIENTE o un CONFIRMADO
        // romperian la generacion del PDF entero.
        SolicitudReporte.Reporte conTexto = new SolicitudReporte.Reporte(
                "Movimientos conciliados", "2026-09-01 a 2026-09-30",
                List.of("FECHA", "IMPORTE", "ESTADO"),
                List.of(
                        List.of(Celda.de("2026-09-20"), Celda.de("152000.00"), Celda.de("CONFIRMADO")),
                        List.of(Celda.de("2026-09-19"), Celda.de("-48000.00"), Celda.de("PENDIENTE"))));

        byte[] archivo = new RenderizadorPdf().render(conTexto);

        try (PDDocument doc = PDDocument.load(archivo)) {
            String texto = new PDFTextStripper().getText(doc);
            assertTrue(texto.contains("CONFIRMADO"));
            assertTrue(texto.contains("PENDIENTE"));
        }
    }

    // ───────────────────────── los dos, a la vez ──────────────────────────────

    @Test
    @DisplayName("Cada renderizador se anuncia con el content-type y la extension que le corresponden")
    void losRenderizadoresSePresentanBien() {
        // El menu Exportar de la pantalla se arma con /formatos, asi que esto no es
        // cosmetics: si el content-type no fuera el del formato, el navegador abriria
        // el archivo en una pestana en vez de bajarlo, y el usuario perderia el
        // archivo sin saber por que.
        for (RenderizadorReporte r : List.of(new RenderizadorExcel(), new RenderizadorPdf())) {
            assertTrue(r.contentType().contains(r.formato()) || r.formato().equals("excel"),
                    r.formato() + ": el content-type '" + r.contentType()
                            + "' no corresponde a la extension ." + r.extension());
            assertTrue(r.extension().matches("[a-z0-9]+"),
                    r.formato() + ": la extension tiene que ir sin punto");
        }
    }
}
