package com.conciliacion.infrastructure.banco;

import com.conciliacion.application.banco.ExtractorBancario;
import com.conciliacion.application.banco.MovimientoBancoCrudo;
import com.conciliacion.application.banco.RangoFechas;
import com.conciliacion.domain.model.CuentaBancaria;
import com.conciliacion.domain.model.OrigenMovimiento;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lee el .xlsx / .xls que baja el usuario del home banking. No habla con ningun banco:
 * es el camino que siempre va a haber.
 *
 * ── LO QUE HACE MEJOR QUE LEERLO A MANO ──────────────────────────────────────
 *
 * 1. No adivina el encoding. Ese problema no existe aca: la celda es texto Unicode o
 *    un numero, no bytes. Un .xlsx con "COMISI&#211;N" llega como "COMISI&#211;N".
 *    (Un .xls viejo, en cambio, si puede traer Windows-1252 adentro. POI lo
 *    convierte por nosotros, que es una de las razones para preferir xlsx.)
 *
 * 2. Entiende los tipos. Una celda de Excel es un double 152000.0 que se VE
 *    "152.000,00", o una fecha real (no la palabra "20/09/2026"). Si el importe se
 *    leyera como texto habria que adivinar el separador de miles y el decimal, que
 *    es donde fallan los parsers ingenuos. Aca se lee la celda, no su dibujo.
 *
 * 3. Busca la hoja y la tabla. Un export de banco suele traer una hoja de
 *    "Portada" o "Resumen" antes de la de movimientos. Recorre todas y se queda con
 *    la primera que tenga fecha e importe, en vez de leer la primera y no ver nada.
 *
 * ── LO QUE NO HACE ───────────────────────────────────────────────────────────
 *
 *  - No lee archivos con macros ni .xls de 95. Si POI no lo abre, el error dice que
 *    no se pudo abrir y el archivo quedo sin tocar, en vez de importar la mitad.
 *  - No inventa comprobante. Sin columna de ID, `comprobante` queda null y esa fuente
 *    NO deduplica: es el limite real de trabajar con archivos y la razon por la que
 *    las APIs son preferibles. Con el UNIQUE de (cuenta, comprobante) no hay forma
 *    de tirar abajo el constraint por null, asi que la fila se puede volver a
 *    cargar sin que nada la frene.
 */
@Component
public class ExtractorExcelBanco implements ExtractorBancario {

    @Override
    public String codigo() { return "EXCEL"; }

    @Override
    public String descripcion() { return "Planilla de Excel del banco (.xlsx, .xls)"; }

    @Override
    public OrigenMovimiento origen() { return OrigenMovimiento.EXCEL; }

    @Override
    public boolean aceptaArchivo() { return true; }

    @Override
    public boolean puedeEjecutarseSolo() { return false; }

    @Override
    public List<String> extensionesAceptadas() { return List.of(".xlsx", ".xls"); }

    @Override
    public List<MovimientoBancoCrudo> extraer(CuentaBancaria cuenta, RangoFechas rango) {
        throw new UnsupportedOperationException(
                "EXCEL necesita un archivo: usar POST /importaciones/archivo");
    }

    @Override
    public List<MovimientoBancoCrudo> extraerDeArchivo(CuentaBancaria cuenta, RangoFechas rango,
                                                        byte[] contenido) {
        List<MovimientoBancoCrudo> salida = new ArrayList<>();
        try (InputStream in = new ByteArrayInputStream(contenido);
             Workbook wb = WorkbookFactory.create(in)) {

            for (int h = 0; h < wb.getNumberOfSheets(); h++) {
                Sheet hoja = wb.getSheetAt(h);
                Tabla tabla = leerTabla(hoja);
                if (tabla == null) {
                    continue;
                }
                salida.addAll(convertir(tabla, rango));
                // Una sola hoja con la tabla. Si dos hojas tuvieran movimientos, el
                // UNIQUE de (cuenta, comprobante) se encargaria del resto, pero es
                // mejor no arriesgar el duplicado silencioso.
                break;
            }
        } catch (IOException e) {
            throw new NoSePudoAbrirException(
                    "No se pudo abrir el archivo de Excel. Si es .xls muy viejo, "
                            + "abrilo en Excel y guardalo como .xlsx. Detalle: " + e.getMessage());
        } catch (RuntimeException e) {
            // POI tira una Hohoo cuando el archivo no es un Excel valido: la
            // cabecera .xls/.xlsx no esta o el zip esta roto.
            throw new NoSePudoAbrirException(
                    "El archivo no parece un Excel legible. Si es un CSV renombrado, "
                            + "subilo por la via CSV. Detalle: " + e.getMessage());
        }
        return salida;
    }

    /**
     * Ubica la tabla de movimientos dentro de una hoja.
     *
     * Busca la primera fila que tenga un encabezado con fecha e importe, y no la
     * fila 0 a ciegas: los export de banco arrancan con el nombre de la cuenta, el
     * IBAN y un par de filas en blanco antes de la tabla.
     *
     * Devuelve null si en esta hoja no hay ninguna tabla con esos dos campos.
     */
    private Tabla leerTabla(Sheet hoja) {
        int primeraDatos = -1;
        Map<String, Integer> cols = null;
        int maxCol = 0;

        for (int r = hoja.getFirstRowNum(); r <= hoja.getLastRowNum() && r < 200; r++) {
            Row fila = hoja.getRow(r);
            if (fila == null) {
                continue;
            }
            List<String> celdas = leerFila(fila);
            Map<String, Integer> intento = FormatoExtracto.mapearColumnas(celdas);
            if (!intento.isEmpty()) {
                cols = intento;
                primeraDatos = r + 1;
                maxCol = celdas.size();
                break;
            }
        }
        if (cols == null) {
            return null;
        }

        List<List<String>> filas = new ArrayList<>();
        for (int r = primeraDatos; r <= hoja.getLastRowNum(); r++) {
            Row fila = hoja.getRow(r);
            if (fila == null) {
                continue;
            }
            List<String> celdas = leerFila(fila, maxCol);
            boolean todaVacia = celdas.stream().allMatch(c -> c == null || c.isBlank());
            // Una fila toda vacia es separador de secciones, no un movimiento roto.
            if (todaVacia) {
                continue;
            }
            filas.add(celdas);
        }
        return new Tabla(cols, filas);
    }

    private List<MovimientoBancoCrudo> convertir(Tabla tabla, RangoFechas rango) {
        List<MovimientoBancoCrudo> salida = new ArrayList<>();
        for (List<String> fila : tabla.filas) {
            try {
                MovimientoBancoCrudo crudo = FormatoExtracto.convertir(fila, tabla.cols);
                if (crudo == null) {
                    continue;
                }
                if (rango.desde() != null && crudo.fecha().isBefore(rango.desde())) {
                    continue;
                }
                if (rango.hasta() != null && crudo.fecha().isAfter(rango.hasta())) {
                    continue;
                }
                salida.add(crudo);
            } catch (RuntimeException ignorada) {
                // Una fila rota no puede tirar abajo las 300 que si vinieron bien.
            }
        }
        return salida;
    }

    /**
     * Lee una fila como texto usando el formato REAL de cada celda.
     *
     * `DataFormatter` es lo importante: una celda numerica con formato
     * "#,##0.00" en una maquina en locale es-AR se ve "152.000,00", y el formatter
     * devuelve ESO. Si en cambio se leyera el double y se armara el texto a mano,
     * habria que reimplementar el separador de miles del sistema, que es
     * exactamente el error que hace que un extractor de Excel importe 100 veces el
     * importe.
     *
     * Por eso el string que sale de aca se lo vuelve a parsear con
     * FormatoExtracto.parseMonto: se pierde precision en el camino, pero se gana
     * que la celda y la columna digan lo mismo.
     */
    private List<String> leerFila(Row fila) {
        return leerFila(fila, (int) fila.getLastCellNum());
    }

    private List<String> leerFila(Row fila, int columnas) {
        List<String> salida = new ArrayList<>(Math.max(columnas, 0));
        for (int c = 0; c < columnas; c++) {
            salida.add(textoDe(fila.getCell(c)));
        }
        return salida;
    }

    /**
     * Celda -&gt; texto, leyendo el VALOR y no el dibujo.
     *
     * Este es el punto donde un lector de Excel se equivoca de verdad. Una celda
     * numerica vale 152000.0 y, con formato "#,##0.00" en es-AR, SE VE "152.000,00".
     * Si se le pidiera el texto con el formato de la celda, el resultado dependeria
     * del locale de la maquina que corre el servidor: en una sale "152.000,00" y en
     * otra "152,000.00", y de ahi sale un importe que vale 152.000 o uno que vale
     * 152. Con dos mil filas, un error de escala pasa desapercibido.
     *
     * Por eso el numero sale crudo y en formato de maquina, con punto decimal y sin
     * separador de miles, y `FormatoExtracto.parseMonto` lo entiende igual que
     * entiende el "1.234,56" de un PDF. Las fechas salen en ISO, que es el primer
     * formato que el parser prueba. Asi el resultado no depende del locale.
     *
     * Una celda de TEXTO con "1.234,56" escrito a mano tambien entra bien, porque
     * `parseMonto` arranca probando el formato argentino. Los dos caminos conviven.
     */
    private String textoDe(Cell celda) {
        if (celda == null) {
            return "";
        }
        CellType tipo = celda.getCellType();
        if (tipo == CellType.FORMULA) {
            // Excel guarda el ultimo resultado calculado de la formula. Si el archivo
            // lo genero algo que no calcula, ese resultado puede no estar, y entonces
            // no hay numero que importar: la fila se cae sola en el parser.
            try {
                return deValor(celda, celda.getCachedFormulaResultType());
            } catch (RuntimeException e) {
                return "";
            }
        }
        return deValor(celda, tipo);
    }

    private String deValor(Cell celda, CellType tipo) {
        if (tipo == CellType.BLANK || tipo == CellType._NONE) {
            return "";
        }
        if (tipo == CellType.BOOLEAN) {
            return String.valueOf(celda.getBooleanCellValue());
        }
        if (tipo == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(celda)) {
                // Fecha real de Excel, que por dentro es un numero serial. Sale ISO.
                return celda.getLocalDateTimeCellValue().toLocalDate().toString();
            }
            return BigDecimal.valueOf(celda.getNumericCellValue()).toPlainString();
        }
        // STRING, y tambien ERROR, que se cae aca y trae su texto.
        String s = celda.getStringCellValue();
        return s == null ? "" : s.trim();
    }

    /** Tabla detectada: el mapa de columnas y las filas de datos. */
    private record Tabla(Map<String, Integer> cols, List<List<String>> filas) {}

    /** El archivo se rompio y no se pudo ni leer ni escribir. 422: no es culpa del servidor. */
    public static class NoSePudoAbrirException extends RuntimeException {
        public NoSePudoAbrirException(String m) { super(m); }
    }
}
