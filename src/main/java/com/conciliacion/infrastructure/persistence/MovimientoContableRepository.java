package com.conciliacion.infrastructure.persistence;

import com.conciliacion.domain.model.EstadoConciliacion;
import com.conciliacion.domain.model.MovimientoContable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface MovimientoContableRepository extends JpaRepository<MovimientoContable, Long> {

    List<MovimientoContable> findByEstado(EstadoConciliacion estado);
    List<MovimientoContable> findByFechaAndImporteAndEsCredito(
            LocalDate fecha, BigDecimal importe, Boolean esCredito);
}