package com.conciliacion.infrastructure.reporte;

import com.conciliacion.application.reporte.SolicitudReporte;
import com.conciliacion.application.reporte.SolicitudReporte.Celda;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPCellEvent;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * Arma el PDF del reporte con OpenPDF.
 *
 * ── POR QUE UN PDF Y NO SOLO UN CSV ────────────────────────────────────────────
 *
 * Porque un PDF va al expediente y un CSV no. Es la razon por la que existe: el
 * archivo se imprime, se firma, se adjunta. Por eso el criterio de busqueda va
 * impreso arriba del archivo y no solo en el nombre.
 *
 * ── LO QUE NO HACE ────────────────────────────────────────────────────────────
 *
 * - No es un Excel disfrazado. No lleva formulas ni filtros, y no pretende: es un
 *   papel. Para trabajar sobre los datos esta el .xlsx.
 * - No pagina a mano. El encabezado se repite solo en cada pagina nueva, que es lo
 *   unico que hace falta para que una tabla de 40 paginas se pueda leer.
 *
 * ── NOTA SOBRE LA API ─────────────────────────────────────────────────────────
 *
 * OpenPDF 1.3.30 es la version mantenida de iText 4, y su API es la de iText 4:
 * `PdfPCell` NO tiene `setBackgroundColor` ni `setBorder`. El fondo se pinta con un
 * `PdfPCellEvent`, que es como lo hacia todo el mundo con iText. Por eso el
 * encabezado gris de abajo no es un `setBackgroundColor(GRIS)`: es una clase de
 * cuatro lineas que dibuja el rectangulo antes de que la celda se escriba.
 */
@Component
public class RenderizadorPdf implements RenderizadorReporte {

    private static final Color GRIS_ENCABEZADO = new Color(0xE4, 0xE4, 0xE4);
    private static final Color GRIS_PIE = new Color(0x88, 0x88, 0x88);
    private static final Color VERDE = new Color(0x1B, 0x7F, 0x3B);
    private static final Color ROJO = new Color(0xB3, 0x26, 0x1E);

    @Override
    public String formato() {
        return "pdf";
    }

    @Override
    public String contentType() {
        return "application/pdf";
    }

    @Override
    public String extension() {
        return "pdf";
    }

    @Override
    public byte[] render(SolicitudReporte.Reporte reporte) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        // A4 apaisado: la tabla tiene 7-8 columnas y en vertical el detalle se parte
        // en tres lineas por fila, que es ilegible.
        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 34, 28);
        try {
            PdfWriter.getInstance(doc, salida);
            doc.open();

            doc.add(new Paragraph(reporte.titulo(), new Font(Font.HELVETICA, 14, Font.BOLD)));
            doc.add(new Paragraph(reporte.subtitulo(), new Font(Font.HELVETICA, 9, Font.NORMAL, GRIS_PIE)));
            doc.add(new Paragraph(" "));

            PdfPTable tabla = new PdfPTable(anchosDe(reporte.columnas().size()));
            tabla.setWidthPercentage(100);
            tabla.setHeaderRows(1);

            Font fEncabezado = new Font(Font.HELVETICA, 8, Font.BOLD);
            for (String col : reporte.columnas()) {
                PdfPCell celda = new PdfPCell(new Phrase(col, fEncabezado));
                celda.setPadding(4);
                celda.setCellEvent(new Fondo(GRIS_ENCABEZADO));
                tabla.addCell(celda);
            }

            int esImporte = indiceDeImporte(reporte.columnas());
            for (List<Celda> fila : reporte.filas()) {
                for (int c = 0; c < reporte.columnas().size(); c++) {
                    String texto = c < fila.size() ? fila.get(c).valor() : "";
                    Font fuente = new Font(Font.HELVETICA, 8, Font.NORMAL);
                    if (c == esImporte) {
                        // El importe va en negrita y con color: un reporte donde los
                        // pagos salen rojos y los cobros verdes se lee de un vistazo.
                        fuente = new Font(Font.HELVETICA, 8, Font.BOLD, colorDe(texto));
                    }
                    PdfPCell celda = new PdfPCell(new Phrase(texto, fuente));
                    celda.setPadding(4);
                    tabla.addCell(celda);
                }
            }
            doc.add(tabla);

            Paragraph pie = new Paragraph(
                    reporte.cantidadDeFilas()
                            + (reporte.cantidadDeFilas() == 1 ? " movimiento" : " movimientos")
                            + " | " + reporte.subtitulo(),
                    new Font(Font.HELVETICA, 8, Font.NORMAL, GRIS_PIE));
            pie.setAlignment(Element.ALIGN_RIGHT);
            pie.setSpacingBefore(8);
            doc.add(pie);

            doc.close();
        } catch (DocumentException e) {
            // DocumentException es de composicion del PDF, no del dato del usuario:
            // aca sale si el renderer esta mal, no si el reporte es raro.
            throw new RuntimeException("No se pudo generar el PDF del reporte", e);
        }
        return salida.toByteArray();
    }

    /**
     * Anchos proporcionales. La segunda columna (detalle o concepto) se lleva la
     * parte grande porque es la unica con texto largo; con el resto todo igual, una
     * "COMISION MANTENIMIENTO CUENTA" se parte en cuatro lineas y la tabla deja de
     * ser legible de un vistazo, que es justo para lo que sirve el PDF.
     */
    private float[] anchosDe(int columnas) {
        float[] pesos = new float[columnas];
        for (int i = 0; i < columnas; i++) {
            pesos[i] = (i == 1) ? 3.2f : 1.3f;
        }
        return pesos;
    }

    private int indiceDeImporte(List<String> columnas) {
        for (int i = 0; i < columnas.size(); i++) {
            if (columnas.get(i).toUpperCase().contains("IMPORTE")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Verde si entra plata, rojo si sale. Si el valor no es un numero (columna
     * "estado", texto libre) queda negro en vez de romper: el color es decorativo,
     * no puede ser la razon de que el PDF no se genere.
     */
    private Color colorDe(String texto) {
        try {
            return new BigDecimal(texto.trim()).signum() < 0 ? ROJO : VERDE;
        } catch (NumberFormatException e) {
            return Color.BLACK;
        }
    }

    /**
     * Pinta el fondo de una celda. Es la unica forma en esta version de OpenPDF:
     * `PdfPCell` no expone `setBackgroundColor`, asi que el rectangulo se dibuja
     * desde el evento que la tabla dispara antes de escribir el texto.
     *
     * Se dibuja sobre `canvases[1]`, la capa vieja, y no sobre `canvases[0]`: la
     * principal. Si se pintara en la principal, el rectangulo taparia el texto que
     * la tabla escribe despues y el encabezado saldria en gris sobre gris.
     */
    private static class Fondo implements PdfPCellEvent {

        private final Color color;

        Fondo(Color color) {
            this.color = color;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[1];
            cb.saveState();
            cb.setColorFill(color);
            cb.rectangle(position.getLeft(), position.getBottom(),
                    position.getWidth(), position.getHeight());
            cb.fill();
            cb.restoreState();
        }
    }
}
