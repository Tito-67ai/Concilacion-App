package com.conciliacion.application.reporte;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Que se exporta y con que formato. Vive en application porque los renderizadores
 * (Excel, PDF) son infraestructura: esto es la pregunta, no la respuesta.
 *
 * El `busqueda` va aca y no solo en el CSV del navegador por una razon concreta: el
 * boton Exportar ofrece los tres formatos y tienen que traer LO MISMO. Si el CSV
 * respeta el texto de "Buscar" y el Excel no, el usuario exporta dos veces, compara
 * los archivos y concluye que uno de los dos esta roto.
 *
 * `lado` decide que se trae:
 *  - PENDIENTES: los dos paneles, con la columna LADO que los distingue. Es lo que
 *    la persona esta mirando cuando apretó Exportar.
 *  - CONCILIADOS: el historico, que es otra pestana y otra consulta.
 */
public record SolicitudReporte(
        Long cuentaBancariaId,
        Long cuentaContableId,
        Long circuitoId,
        LocalDate desde,
        LocalDate hasta,
        String busqueda,
        Lado lado) {

    public enum Lado { PENDIENTES, CONCILIADOS }

    public SolicitudReporte {
        lado = lado == null ? Lado.PENDIENTES : lado;
    }

    /** El filtro de pantalla, siempre con los mismos nombres. */
    public static SolicitudReporte deFiltro(Long cuentaBancariaId, Long cuentaContableId,
                                            Long circuitoId, LocalDate desde, LocalDate hasta,
                                            String busqueda, String lado) {
        return new SolicitudReporte(cuentaBancariaId, cuentaContableId, circuitoId, desde, hasta,
                busqueda, "CONCILIADOS".equalsIgnoreCase(lado) ? Lado.CONCILIADOS : Lado.PENDIENTES);
    }

    /** Texto a buscar en detalle/concepto/comprobante, ya en minusculas. Null = sin filtro. */
    public String busquedaNormalizada() {
        if (busqueda == null) {
            return null;
        }
        String b = busqueda.trim().toLowerCase(Locale.ROOT);
        return b.isEmpty() ? null : b;
    }

    /** Una celda del reporte, siempre como texto. */
    public record Celda(String valor) {
        public static Celda de(Object v) {
            return new Celda(v == null ? "" : String.valueOf(v));
        }
    }

    /** El archivo completo: encabezado, filas y un pie con el criterio. */
    public record Reporte(String titulo, String subtitulo, List<String> columnas, List<List<Celda>> filas) {

        public int cantidadDeFilas() {
            return filas.size();
        }
    }
}
