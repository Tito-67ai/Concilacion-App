package com.conciliacion.infrastructure.reporte;

import com.conciliacion.application.reporte.SolicitudReporte;
import com.conciliacion.application.reporte.SolicitudReporte.Celda;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * Arma el .xlsx del reporte con Apache POI.
 *
 * ── POR QUE ES UN NUMERO Y NO UN TEXTO ────────────────────────────────────────
 *
 * Este es el motivo de que el .xlsx sea mas util que el CSV para el mismo dato, y
 * por lo que vale la pena generarlo en vez de exportar CSV y renombrarlo.
 *
 * Si la celda del importe dice el texto "152.000,00", Excel lo abre como texto: no se
 * puede sumar, no se puede graficar, y un "Buscar" por 152000 no lo encuentra. Con
 * `setCellValue(BigDecimal)` la celda es un numero, y Excel le pone el formato de
 * miles y dos decimales solo. El archivo abre bien en cualquier locale y en
 * LibreOffice, sin depender de la maquina que lo mire.
 *
 * El signo se mantiene en la celda (el numero es el que es, positivo o negativo) y
 * el color va aparte, como en el PDF. Un reporte donde los pagos salen en rojo y los
 * cobros en verde se lee de un vistazo, pero el dato sigue siendo el dato.
 */
@Component
public class RenderizadorExcel implements RenderizadorReporte {

    @Override
    public String formato() {
        return "excel";
    }

    @Override
    public String contentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    public String extension() {
        return "xlsx";
    }

    @Override
    public byte[] render(SolicitudReporte.Reporte reporte) {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream salida = new ByteArrayOutputStream()) {

            Sheet hoja = wb.createSheet("Conciliacion");
            hoja.setDisplayGridlines(false);

            // Fila 0: titulo. Fila 1: criterio. Fila 2: vacia.
            Row titulo = hoja.createRow(0);
            CellStyle fTitulo = wb.createCellStyle();
            Font boldGrande = wb.createFont();
            boldGrande.setBold(true);
            boldGrande.setFontHeightInPoints((short) 13);
            fTitulo.setFont(boldGrande);
            titulo.createCell(0).setCellValue(reporte.titulo());
            titulo.getCell(0).setCellStyle(fTitulo);

            Row criterio = hoja.createRow(1);
            CellStyle fCriterio = wb.createCellStyle();
            Font gris = wb.createFont();
            gris.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            fCriterio.setFont(gris);
            criterio.createCell(0).setCellValue(reporte.subtitulo());
            criterio.getCell(0).setCellStyle(fCriterio);

            int filaEncabezado = 3;
            int columnaImporte = indiceDeImporte(reporte.columnas());

            // Encabezado
            Row encabezado = hoja.createRow(filaEncabezado);
            CellStyle fEncabezado = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            fEncabezado.setFont(bold);
            fEncabezado.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            fEncabezado.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            fEncabezado.setBorderBottom(BorderStyle.THIN);
            fEncabezado.setAlignment(HorizontalAlignment.LEFT);
            fEncabezado.setVerticalAlignment(VerticalAlignment.CENTER);
            for (int c = 0; c < reporte.columnas().size(); c++) {
                celda(encabezado, c).setCellValue(reporte.columnas().get(c));
                celda(encabezado, c).setCellStyle(fEncabezado);
            }

            // Estilo del importe: numero con miles y dos decimales, alineado a la
            // derecha. Es lo que hace que la columna se pueda sumar en Excel.
            CellStyle fMoneda = wb.createCellStyle();
            fMoneda.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            fMoneda.setAlignment(HorizontalAlignment.RIGHT);
            Font verde = wb.createFont();
            verde.setColor(IndexedColors.GREEN.getIndex());
            Font rojo = wb.createFont();
            rojo.setColor(IndexedColors.DARK_RED.getIndex());

            CellStyle fMonedaVerde = wb.createCellStyle();
            fMonedaVerde.cloneStyleFrom(fMoneda);
            fMonedaVerde.setFont(verde);

            CellStyle fMonedaRojo = wb.createCellStyle();
            fMonedaRojo.cloneStyleFrom(fMoneda);
            fMonedaRojo.setFont(rojo);

            int r = filaEncabezado + 1;
            for (List<Celda> fila : reporte.filas()) {
                Row row = hoja.createRow(r++);
                for (int c = 0; c < reporte.columnas().size(); c++) {
                    String texto = c < fila.size() ? fila.get(c).valor() : "";
                    org.apache.poi.ss.usermodel.Cell celda = celda(row, c);
                    if (c == columnaImporte) {
                        BigDecimal n = aNumero(texto);
                        if (n != null) {
                            celda.setCellValue(n.doubleValue());
                            celda.setCellStyle(n.signum() < 0 ? fMonedaRojo : fMonedaVerde);
                            continue;
                        }
                    }
                    celda.setCellValue(texto);
                }
            }

            // Congelar el encabezado: con 500 filas, sin esto hay que volver arriba
            // scrollando para leer de que columna se trata cada numero.
            hoja.createFreezePane(0, filaEncabezado + 1);
            hoja.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    filaEncabezado, Math.max(filaEncabezado, r - 1), 0, reporte.columnas().size() - 1));
            anchos(hoja, reporte.columnas());

            wb.write(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            // Con un XSSFWorkbook en memoria esto es practicamente imposible: no hay
            // disco, no hay red. Si igual pasara, es un bug y no un dato del usuario,
            // asi que va como RuntimeException y no como 422.
            throw new RuntimeException("No se pudo generar el Excel del reporte", e);
        }
    }

    private org.apache.poi.ss.usermodel.Cell celda(Row fila, int c) {
        org.apache.poi.ss.usermodel.Cell celda = fila.getCell(c);
        return celda == null ? fila.createCell(c) : celda;
    }

    private int indiceDeImporte(List<String> columnas) {
        for (int i = 0; i < columnas.size(); i++) {
            if (columnas.get(i).toUpperCase().contains("IMPORTE")) {
                return i;
            }
        }
        return -1;
    }

    private BigDecimal aNumero(String texto) {
        try {
            return new BigDecimal(texto.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Anchos en caracteres, que es como Excel mide. El detalle se lleva la mitad
     * porque es la columna con texto largo; con el ancho por defecto de 8 caracteres
     * un "COMISION MANTENIMIENTO CUENTA" se ve cortado.
     */
    private void anchos(Sheet hoja, List<String> columnas) {
        for (int c = 0; c < columnas.size(); c++) {
            String nombre = columnas.get(c).toUpperCase();
            int ancho;
            if (nombre.contains("DETALLE") || nombre.contains("CONCEPTO") || nombre.contains("COMPROBANTES")) {
                ancho = 42;
            } else if (nombre.contains("CUENTA") || nombre.contains("CIRCUITO")) {
                ancho = 24;
            } else if (nombre.contains("IMPORTE")) {
                ancho = 16;
            } else if (nombre.contains("FECHA")) {
                ancho = 12;
            } else {
                ancho = 14;
            }
            hoja.setColumnWidth(c, ancho * 256);
        }
    }
}
