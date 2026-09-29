package com.conciliacion.infrastructure.web;

import com.conciliacion.application.reporte.ReporteService;
import com.conciliacion.application.reporte.SolicitudReporte;
import com.conciliacion.infrastructure.reporte.RenderizadorReporte;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Descarga del reporte en el formato que pida el usuario.
 *
 * ── POR QUE UN SOLO ENDPOINT Y NO UNO POR FORMATO ─────────────────────────────
 *
 * `GET /api/exportaciones/{formato}` con `formato` = excel | pdf.
 *
 * La alternativa obvious es un `@GetMapping("/excel")` y otro `@GetMapping("/pdf")`,
 * con el controller llamando a cada renderizador por su nombre. Eso funciona y es
 * mas corto, pero cada formato nuevo obliga a tocar esta clase. Con la lista de
 * renderizadores inyectada, el menu de la pantalla se arma con `/formatos` y
 * `/pdf` no existo hasta que alguien lo escribio.
 *
 * El CSV NO esta aca y esa decision es deliberada: se arma en el navegador, con los
 * datos que ya esta mostrando, y sale con ";" y BOM para que Excel en es-AR no lea
 * la coma como separador de decimales. Moverlo aca seria meter una libreria de CSV
 * mas dependencias para hacer en el servidor algo que en el cliente ya sale bien.
 */
@RestController
@RequestMapping("/api/exportaciones")
public class ExportacionController {

    private final ReporteService reportes;
    private final Map<String, RenderizadorReporte> porFormato = new LinkedHashMap<>();

    public ExportacionController(ReporteService reportes, List<RenderizadorReporte> renderizadores) {
        this.reportes = reportes;
        for (RenderizadorReporte r : renderizadores) {
            porFormato.put(r.formato(), r);
        }
    }

    /**
     * Que formatos hay. Es lo que lista el boton Exportar, asi que agregar un
     * renderizador nuevo lo hace aparecer solo.
     */
    @GetMapping("/formatos")
    public List<InfoFormato> formatos() {
        return porFormato.values().stream().map(InfoFormato::de).toList();
    }

    @GetMapping("/{formato}")
    public ResponseEntity<byte[]> exportar(
            @PathVariable String formato,
            @RequestParam(required = false) Long cuentaBancariaId,
            @RequestParam(required = false) Long cuentaContableId,
            @RequestParam(required = false) Long circuitoId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) String busqueda,
            @RequestParam(required = false) String lado) {

        RenderizadorReporte r = porFormato.get(formato.toLowerCase());
        if (r == null) {
            throw new IllegalArgumentException("No existe el formato de exportacion '" + formato
                    + "'. Disponibles: " + String.join(", ", porFormato.keySet()));
        }

        SolicitudReporte solicitud = SolicitudReporte.deFiltro(
                cuentaBancariaId, cuentaContableId, circuitoId, desde, hasta, busqueda, lado);
        byte[] archivo = r.render(reportes.armar(solicitud));

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(r.contentType()))
                .contentLength(archivo.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, nombreDeArchivo(r, solicitud).toString())
                .body(archivo);
    }

    /**
     * El nombre del archivo dice que TRAE, no solo cuando.
     *
     * "reporte.xlsx" en una carpeta con seis descargas de la misma semana no
     * distinguisho nada. Con el rango de fechas y el lado adentro, se sabe sin
     * abrirlo cual es la conciliacion de septiembre y cual la de agosto.
     */
    private ContentDisposition nombreDeArchivo(RenderizadorReporte r, SolicitudReporte s) {
        String rango = (s.desde() == null ? "inicio" : s.desde())
                + "-a-" + (s.hasta() == null ? "hoy" : s.hasta());
        String nombre = "conciliacion-" + s.lado().name().toLowerCase() + "-" + rango + "." + r.extension();
        return ContentDisposition.attachment()
                .filename(nombre, StandardCharsets.UTF_8)
                .build();
    }

    /** Lo que el boton Exportar necesita para armar el menu. */
    public record InfoFormato(String formato, String contentType, String extension, String etiqueta) {
        static InfoFormato de(RenderizadorReporte r) {
            return new InfoFormato(r.formato(), r.contentType(), r.extension(), etiquetaDe(r.formato()));
        }

        private static String etiquetaDe(String formato) {
            return switch (formato.toLowerCase()) {
                case "excel" -> "Excel (.xlsx)";
                case "pdf" -> "PDF";
                default -> formato.toUpperCase();
            };
        }
    }
}
