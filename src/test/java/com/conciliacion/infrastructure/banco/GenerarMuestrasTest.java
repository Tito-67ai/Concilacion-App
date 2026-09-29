package com.conciliacion.infrastructure.banco;

import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Escribe los mismos archivos del test a disco, para subirlos por HTTP a mano y
 * ver la pantalla entera. NO es un test: no falla nunca, no afirma nada. Es una
 * herramienta. Por eso va en target/, que se borra solo con `mvn clean`, y no en
 * src/test/resources, que es donde nadie se acuerda de borrar las muestras viejas.
 */
class GenerarMuestrasTest {

    private static final Path CARPETA = Path.of("target", "muestras");

    @Test
    void escribirMuestras() throws Exception {
        Files.createDirectories(CARPETA);
        Files.write(CARPETA.resolve("galicia-septiembre.xlsx"), excel());
        Files.write(CARPETA.resolve("galicia-septiembre.pdf"), pdf());
        System.out.println("### muestras en " + CARPETA.toAbsolutePath());
    }

    private byte[] excel() throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            Sheet portada = wb.createSheet("Portada");
            portada.createRow(0).createCell(0).setCellValue("BANCO GALICIA - EXTRACTO DE CUENTA");
            portada.createRow(1).createCell(0).setCellValue("0000003100000003079083");
            portada.createRow(2).createCell(0).setCellValue("Periodo 01/09/2026 al 30/09/2026");

            Sheet hoja = wb.createSheet("Movimientos");
            Row enc = hoja.createRow(2);
            String[] cabeceras = {"FECHA", "DETALLE", "IMPORTE", "SIGNO", "COMPROBANTE"};
            for (int c = 0; c < cabeceras.length; c++) {
                enc.createCell(c).setCellValue(cabeceras[c]);
            }
            CellStyle conMiles = wb.createCellStyle();
            conMiles.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));

            Object[][] datos = {
                    {"20/09/2026", "COBRO FACTURA B-1150", 98000d, "CREDITO", "GAL-9001"},
                    {"17/09/2026", "TRANSFERENCIA RECIBIDA DE ACME", 45500.40d, "CREDITO", "GAL-9002"},
                    {"16/09/2026", "PAGO PROVEEDOR ACME", -22500.75d, "DEBITO", "GAL-9003"}};
            int r = 3;
            for (Object[] d : datos) {
                Row fila = hoja.createRow(r++);
                fila.createCell(0).setCellValue((String) d[0]);
                fila.createCell(1).setCellValue((String) d[1]);
                fila.createCell(2).setCellValue((Double) d[2]);
                fila.getCell(2).setCellStyle(conMiles);
                fila.createCell(3).setCellValue((String) d[3]);
                fila.createCell(4).setCellValue((String) d[4]);
            }
            wb.write(salida);
            return salida.toByteArray();
        }
    }

    private byte[] pdf() throws Exception {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 30, 30, 40, 30);
        PdfWriter writer = PdfWriter.getInstance(doc, salida);
        doc.open();
        PdfContentByte cb = writer.getDirectContent();
        BaseFont f = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);

        cb.beginText();
        cb.setFontAndSize(f, 9);
        cb.setTextMatrix(50, 800);
        cb.showText("BANCO GALICIA - EXTRACTO DE CUENTA 0000003100000003079083");
        cb.endText();

        float[] x = {50, 110, 320, 430};
        cb.beginText();
        cb.setFontAndSize(f, 8);
        String[] enc = {"Fecha", "Detalle", "Importe", "Saldo"};
        for (int c = 0; c < x.length; c++) {
            cb.setTextMatrix(x[c], 760);
            cb.showText(enc[c]);
        }
        cb.endText();

        String[][] filas = {
                {"20/09/2026", "COBRO FACTURA C-1201", "310.000,00", "1.610.000,00"},
                {"17/09/2026", "PAGO PROVEEDOR ACME", "-12.750,50", "1.300.000,00"},
                {"16/09/2026", "COMISION MANTENIMIENTO CUENTA", "-1.850,00", "1.312.750,50"}};

        float y = 740;
        for (String[] fila : filas) {
            cb.beginText();
            cb.setFontAndSize(f, 8);
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
}
