package com.conciliacion.infrastructure.web;

import com.conciliacion.application.ConciliacionService;
import com.conciliacion.domain.model.Conciliacion;
import com.conciliacion.domain.model.MovimientoBancario;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/conciliaciones")
public class ConciliacionController {

    private final ConciliacionService service;

    public ConciliacionController(ConciliacionService service) {
        this.service = service;
    }

    @GetMapping("/pendientes")
    public List<MovimientoBancario> pendientes() {
        return service.pendientesDeBanco();
    }

    @PostMapping("/{id}/autoconciliar")
    public Optional<Conciliacion> autoconciliar(@PathVariable Long id) {
        return service.autoconciliar(id);
    }
}