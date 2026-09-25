package com.conciliacion.application;

import com.conciliacion.domain.model.Conciliacion;
import com.conciliacion.domain.model.EstadoConciliacion;
import com.conciliacion.domain.model.MovimientoBancario;
import com.conciliacion.domain.model.MovimientoContable;
import com.conciliacion.domain.model.OrigenMovimiento;
import com.conciliacion.domain.model.TipoMovimiento;
import com.conciliacion.infrastructure.persistence.ConciliacionRepository;
import com.conciliacion.infrastructure.persistence.MovimientoBancarioRepository;
import com.conciliacion.infrastructure.persistence.MovimientoContableRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class ConciliacionService {

    private final MovimientoBancarioRepository bancoRepo;
    private final MovimientoContableRepository contableRepo;
    private final ConciliacionRepository conciliacionRepo;

    public ConciliacionService(MovimientoBancarioRepository bancoRepo,
                               MovimientoContableRepository contableRepo,
                               ConciliacionRepository conciliacionRepo) {
        this.bancoRepo = bancoRepo;
        this.contableRepo = contableRepo;
        this.conciliacionRepo = conciliacionRepo;
    }

    public List<MovimientoBancario> pendientesDeBanco() {
        List<MovimientoBancario> out = new ArrayList<>();
        for (MovimientoBancario b : bancoRepo.findAll()) {
            if (EstadoConciliacion.PENDIENTE.equals(b.getEstado())) out.add(b);
        }
        return out;
    }

    @Transactional
    public Optional<Conciliacion> autoconciliar(Long idBanco) {
        MovimientoBancario bco = bancoRepo.findById(idBanco).orElse(null);
        if (bco == null || !EstadoConciliacion.PENDIENTE.equals(bco.getEstado())) return Optional.empty();

        List<MovimientoContable> pares = new ArrayList<>();
        for (MovimientoContable c : contableRepo.findAll()) {
            if (!EstadoConciliacion.PENDIENTE.equals(c.getEstado())) continue;
            if (!Objects.equals(c.getFecha(), bco.getFecha())) continue;
            if (!Objects.equals(c.getImporte(), bco.getImporte())) continue;
            if (!Objects.equals(c.getEsCredito(), bco.getEsCredito())) continue;
            pares.add(c);
        }
        if (pares.size() != 1) return Optional.empty();

        MovimientoContable par = pares.get(0);
        par.setEstado(EstadoConciliacion.CONCILIADO);
        bco.setEstado(EstadoConciliacion.CONCILIADO);

        Conciliacion nueva = new Conciliacion(
                EstadoConciliacion.CONCILIADO,
                TipoMovimiento.OTRO,
                OrigenMovimiento.OTRO,
                bco.getFecha(),
                textoDe(bco.getDetalle(), par.getConcepto()),
                bco.getImporte(),
                bco.getEsCredito(),
                LocalDateTime.now());

        Conciliacion guardada = conciliacionRepo.save(nueva);
        bancoRepo.save(bco);
        contableRepo.save(par);
        return Optional.of(guardada);
    }

    private String textoDe(String b, String c) {
        if (c != null && !c.isBlank()) return c;
        return (b != null && !b.isBlank()) ? b : "movimiento conciliado";
    }
}