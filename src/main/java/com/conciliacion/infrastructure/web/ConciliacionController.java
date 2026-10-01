package com.conciliacion.infrastructure.web;

import com.conciliacion.application.ConciliacionService;
import com.conciliacion.application.FiltroConciliacion;
import com.conciliacion.application.OpcionesFiltro;
import com.conciliacion.application.ParejaConciliacion;
import com.conciliacion.application.ResultadoConciliacion;
import com.conciliacion.application.empresa.Empresa;
import com.conciliacion.application.empresa.EmpresasXubio;
import com.conciliacion.domain.model.Conciliacion;
import com.conciliacion.domain.model.EstadoConciliacion;
import com.conciliacion.domain.model.MovimientoBancario;
import com.conciliacion.domain.model.MovimientoContable;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conciliaciones")
public class ConciliacionController {

    private final ConciliacionService service;
    private final EmpresasXubio empresas;

    public ConciliacionController(ConciliacionService service, EmpresasXubio empresas) {
        this.service = service;
        this.empresas = empresas;
    }

    /**
     * Empresas que se pueden operar, una por App Cliente de Xubio.
     *
     * El nombre de cada una lo trae `GET /miempresa`, no la configuracion, asi que
     * la lista refleja lo que Xubio tiene de verdad y no lo que alguien escribio
     * hace tres meses.
     *
     * Se devuelve 200 aunque ninguna tenga acceso: la respuesta es la lista con
     * `estado = SIN_ACCESO` y el motivo en cada una, y la pantalla los muestra
     * aparte. Un 503 aca seria peor: la barra quedaria en error y no se podrian
     * elegir las empresas que si funcionan.
     *
     * Con la fuente apagada devuelve una lista vacia, que NO es lo mismo que un
     * error: la pantalla la muestra como "ninguna empresa configurada".
     */
    @GetMapping("/empresas")
    public List<Empresa> empresas() {
        return empresas.consultar();
    }

    /** Opciones de los desplegables del filtro, en una sola llamada. */
    @GetMapping("/filtros/opciones")
    public OpcionesFiltro opciones() {
        return service.opciones();
    }

    /**
     * Pendientes. Solo aplican cuenta bancaria y fechas: un pendiente todavia no
     * tiene contrapartida contable, asi que no hay cuenta contable ni circuito.
     */
    @GetMapping("/pendientes")
    public List<MovimientoBancario> pendientes(
            @RequestParam(required = false) Long cuentaBancariaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return service.pendientes(new FiltroConciliacion(cuentaBancariaId, null, null, desde, hasta, null));
    }

    /** Historial de conciliaciones, con los 4 filtros. */
    @GetMapping
    public List<Conciliacion> conciliaciones(
            @RequestParam(required = false) Long cuentaBancariaId,
            @RequestParam(required = false) Long cuentaContableId,
            @RequestParam(required = false) Long circuitoId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) EstadoConciliacion estado) {
        return service.conciliaciones(new FiltroConciliacion(cuentaBancariaId, cuentaContableId,
                circuitoId, desde, hasta, estado));
    }

    /**
     * Panel derecho de la pantalla de matching: los contables que todavia no tienen
     * pareja. Filtra por cuenta contable, circuito y fechas. NO por cuenta bancaria:
     * en este lado todavia no hay cuenta bancaria, se conoce cuando se elige la
     * pareja con la fila del otro panel.
     */
    @GetMapping("/pendientes-contables")
    public List<MovimientoContable> pendientesContables(
            @RequestParam(required = false) Long cuentaContableId,
            @RequestParam(required = false) Long circuitoId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return service.pendientesContables(
                new FiltroConciliacion(null, cuentaContableId, circuitoId, desde, hasta, null));
    }

    /**
     * Conciliacion manual: POST con el par, no con un id suelto, porque "conciliar
     * estos dos" es una accion sobre un par.
     *
     * 404 si alguno de los dos no existe, 409 si alguno ya no esta pendiente, 409 con
     * detalle si son de signos distintos.
     */
    @PostMapping
    public ResponseEntity<?> conciliar(@Valid @RequestBody ParejaConciliacion pareja) {
        ResultadoConciliacion r = service.conciliarManualmente(pareja.idBanco(), pareja.idContable());
        return switch (r.estado()) {
            case OK -> ResponseEntity.ok(r.conciliacion());
            case NO_EXISTE -> ResponseEntity.notFound().build();
            case SIGNO_INCOMPATIBLE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "SIGNO_INCOMPATIBLE", "detalle", r.detalle()));
            case SIN_PAR -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "SIN_PAR",
                            "detalle", "Alguno de los dos movimientos ya fue conciliado o descartado."));
        };
    }

    /**
     * 404 si el id no existe, 409 si existe pero no se puede conciliar.
     * Antes devolvia `Optional` directo, que Jackson serializa como `null` con 200:
     * el cliente no podia distinguir "no existe" de "no matcheo".
     */
    @PostMapping("/{id}/autoconciliar")
    public ResponseEntity<Conciliacion> autoconciliar(@PathVariable Long id) {
        ResultadoConciliacion r = service.autoconciliar(id);
        return switch (r.estado()) {
            case OK -> ResponseEntity.ok(r.conciliacion());
            case NO_EXISTE -> ResponseEntity.notFound().build();
            case SIN_PAR -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case SIGNO_INCOMPATIBLE -> ResponseEntity.status(HttpStatus.CONFLICT).build();
        };
    }

    /** Saca de la cola lo que no va a tener contrapartida (comisiones, traspasos). */
    @PostMapping("/{id}/descartar")
    public ResponseEntity<Void> descartar(@PathVariable Long id) {
        ResultadoConciliacion r = service.descartar(id);
        return switch (r.estado()) {
            case OK -> ResponseEntity.noContent().build();
            case NO_EXISTE -> ResponseEntity.notFound().build();
            // SIGNO_INCOMPATIBLE no puede salir de descartar: descartar no mira el
            // otro lado. El case esta porque el switch tiene que ser exhaustivo, y
            // omitirlo seria cambiar el contrato del enum en silencio.
            case SIN_PAR, SIGNO_INCOMPATIBLE -> ResponseEntity.status(HttpStatus.CONFLICT).build();
        };
    }
}
