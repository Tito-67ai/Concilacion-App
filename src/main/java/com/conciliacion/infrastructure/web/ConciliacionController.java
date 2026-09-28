package com.conciliacion.infrastructure.web;

import com.conciliacion.application.ConciliacionService;
import com.conciliacion.application.FiltroConciliacion;
import com.conciliacion.application.OpcionesFiltro;
import com.conciliacion.application.ResultadoConciliacion;
import com.conciliacion.domain.model.Conciliacion;
import com.conciliacion.domain.model.EstadoConciliacion;
import com.conciliacion.domain.model.MovimientoBancario;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/conciliaciones")
public class ConciliacionController {

    private final ConciliacionService service;

    public ConciliacionController(ConciliacionService service) {
        this.service = service;
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
        };
    }

    /** Saca de la cola lo que no va a tener contrapartida (comisiones, traspasos). */
    @PostMapping("/{id}/descartar")
    public ResponseEntity<Void> descartar(@PathVariable Long id) {
        ResultadoConciliacion r = service.descartar(id);
        return switch (r.estado()) {
            case OK -> ResponseEntity.noContent().build();
            case NO_EXISTE -> ResponseEntity.notFound().build();
            case SIN_PAR -> ResponseEntity.status(HttpStatus.CONFLICT).build();
        };
    }
}
