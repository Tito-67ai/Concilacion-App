package com.conciliacion.infrastructure.reporte;

import com.conciliacion.application.reporte.SolicitudReporte;

/**
 * Un formato de salida del reporte. Excel y PDF son dos implementaciones.
 *
 * Existe como interfaz para que el boton Exportar de la pantalla sea el mismo para
 * los dos: el frontend pide la lista de formatos al backend y arma el menu con lo
 * que le contesten. Agregar un cuarto formato (HTML, JSON) es escribir esta
 * implementacion y nada mas; ni el controller ni la pantalla se tocan.
 *
 * `formato`, `contentType` y `extension` van juntos porque los tres tienen que
 * coincidir con lo que se le ofrece al usuario. Si el menu dice "PDF" y el
 * Content-Type dice `application/octet-stream`, el navegador lo abre en una pestana
 * en vez de bajarlo.
 */
public interface RenderizadorReporte {

    /** Identificador para la URL: /api/exportaciones/{formato}. */
    String formato();

    /** Lo que va en el header Content-Type. */
    String contentType();

    /** Sin el punto: "xlsx", "pdf". */
    String extension();

    byte[] render(SolicitudReporte.Reporte reporte);
}
