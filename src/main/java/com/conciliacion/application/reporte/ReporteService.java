package com.conciliacion.application.reporte;

import com.conciliacion.application.reporte.SolicitudReporte.Celda;
import com.conciliacion.domain.model.EstadoConciliacion;
import com.conciliacion.domain.model.MovimientoBancario;
import com.conciliacion.domain.model.MovimientoContable;
import com.conciliacion.infrastructure.persistence.ConciliacionRepository;
import com.conciliacion.infrastructure.persistence.MovimientoBancarioRepository;
import com.conciliacion.infrastructure.persistence.MovimientoContableRepository;
import com.conciliacion.domain.model.Conciliacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Arma el reporte que el usuario quiere bajar, sin saber todavia en que formato.
 *
 * Vive separado de los renderizadores (Excel y PDF) porque el formato es una
 * decision de presentacion y el contenido no: si el dia que viene hay un Fourth
 * formato, se agrega un renderizador y esta clase no se toca.
 *
 * ── EL CONCEPTO QUE SE EXPORTA ────────────────────────────────────────────────
 *
 * Dos, y no uno, porque la pantalla tiene dos Cosas distintas y el boton Exportar
 * esta parado arriba de las dos:
 *
 *  - PENDIENTES: los dos paneles de la pantalla de matching, con una columna LADO
 *    que dice de que panel salio cada fila. Es lo que la persona esta mirando.
 *  - CONCILIADOS: el historico de conciliaciones, que es la otra pestana.
 *
 * Por eso el `lado` viene en la solicitud y no se deduce de los filtros: con los
 * mismos filtros, las dos pestañas muestran cosas distintas.
 */
@Service
public class ReporteService {

    private final MovimientoBancarioRepository bancoRepo;
    private final MovimientoContableRepository contableRepo;
    private final ConciliacionRepository conciliacionRepo;

    public ReporteService(MovimientoBancarioRepository bancoRepo,
                          MovimientoContableRepository contableRepo,
                          ConciliacionRepository conciliacionRepo) {
        this.bancoRepo = bancoRepo;
        this.contableRepo = contableRepo;
        this.conciliacionRepo = conciliacionRepo;
    }

    @Transactional(readOnly = true)
    public SolicitudReporte.Reporte armar(SolicitudReporte s) {
        return s.lado() == SolicitudReporte.Lado.CONCILIADOS ? conciliados(s) : pendientes(s);
    }

    private SolicitudReporte.Reporte pendientes(SolicitudReporte s) {
        // Misma logica de filtros que la pantalla: cuenta bancaria aca, cuenta
        // contable y circuito alla. Si se mezclaran, el panel derecho vendria filtrado
        // por una cuenta bancaria que todavia no se eligio.
        List<MovimientoBancario> banco = bancoRepo.buscarConFiltros(
                EstadoConciliacion.PENDIENTE, s.cuentaBancariaId(), s.desde(), s.hasta());
        List<MovimientoContable> contable = contableRepo.buscarPendientesConFiltros(
                EstadoConciliacion.PENDIENTE, s.cuentaContableId(), s.circuitoId(), s.desde(), s.hasta());

        String q = s.busquedaNormalizada();

        List<List<Celda>> filas = new ArrayList<>();
        for (MovimientoBancario m : banco) {
            if (!coincide(q, m.getDetalle(), m.getComprobante())) {
                continue;
            }
            filas.add(List.of(
                    Celda.de("BANCO"),
                    Celda.de(m.getFecha()),
                    Celda.de(m.getDetalle()),
                    Celda.de(m.getComprobante()),
                    Celda.de(m.getImporte().toPlainString()),
                    Celda.de(m.getCuentaBancaria().getBanco()),
                    Celda.de(m.getOrigen())));
        }
        for (MovimientoContable m : contable) {
            if (!coincide(q, m.getConcepto(), m.getComprobante())) {
                continue;
            }
            filas.add(List.of(
                    Celda.de("CONTABLE"),
                    Celda.de(m.getFecha()),
                    Celda.de(m.getConcepto()),
                    Celda.de(m.getComprobante()),
                    Celda.de(m.getImporte().toPlainString()),
                    Celda.de(m.getCuentaContable().getCodigo() + " / " + m.getCircuito().getNombre()),
                    Celda.de(m.getOrigen())));
        }
        return new SolicitudReporte.Reporte("Movimientos a conciliar", criterio(s),
                List.of("LADO", "FECHA", "DETALLE", "COMPROBANTE", "IMPORTE", "CUENTA", "ORIGEN"),
                filas);
    }

    private SolicitudReporte.Reporte conciliados(SolicitudReporte s) {
        List<Conciliacion> lista = conciliacionRepo.buscarConFiltros(
                s.cuentaBancariaId(), s.cuentaContableId(), s.circuitoId(), s.desde(), s.hasta(),
                null);
        String q = s.busquedaNormalizada();

        List<List<Celda>> filas = new ArrayList<>();
        for (Conciliacion c : lista) {
            String bancoComp = c.getMovimientoBancario().getComprobante();
            String contableComp = c.getMovimientoContable().getComprobante();
            if (!coincide(q, c.getDetalle(), bancoComp, contableComp)) {
                continue;
            }
            filas.add(List.of(
                    Celda.de(c.getFecha()),
                    Celda.de(c.getDetalle()),
                    Celda.de(c.getImporte().toPlainString()),
                    Celda.de(c.getCuentaBancaria().getBanco()),
                    Celda.de(c.getCuentaContable().getCodigo()),
                    Celda.de(c.getCircuito().getNombre()),
                    Celda.de(bancoComp + " / " + contableComp),
                    Celda.de(c.getEstado())));
        }
        return new SolicitudReporte.Reporte("Movimientos conciliados", criterio(s),
                List.of("FECHA", "DETALLE", "IMPORTE", "BANCO", "CUENTA CONTABLE", "CIRCUITO",
                        "COMPROBANTES", "ESTADO"),
                filas);
    }

    /**
     * El texto de "Buscar" de la pantalla, con la misma regla que usa el panel: si
     * viene vacio, trae todo. Case-insensitive, como en la UI.
     */
    private boolean coincide(String q, String... textos) {
        if (q == null) {
            return true;
        }
        for (String t : textos) {
            if (t != null && t.toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    /**
     * El criterio, escrito en el archivo.
     *
     * Va impreso en el reporte por una razon que se aprende tarde: un PDF o una
     * planilla que dice "5 movimientos" sin decir de cuando, es un papel que a las
     * dos semanas no se puede auditar. Alguien lo encuentra, lo adjunta a un
     * expediente, y no hay forma de saber que filtro lo produjo.
     */
    private String criterio(SolicitudReporte s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.desde() == null ? "inicio" : s.desde().toString())
                .append(" a ")
                .append(s.hasta() == null ? "hoy" : s.hasta().toString());
        if (s.busquedaNormalizada() != null) {
            sb.append(" | contiene \"").append(s.busqueda().trim()).append("\"");
        }
        return sb.toString();
    }
}
