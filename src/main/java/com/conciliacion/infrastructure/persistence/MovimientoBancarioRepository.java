package com.conciliacion.infrastructure.persistence;

import com.conciliacion.domain.model.EstadoConciliacion;
import com.conciliacion.domain.model.MovimientoBancario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface MovimientoBancarioRepository extends JpaRepository<MovimientoBancario, Long> {

    List<MovimientoBancario> findByCbuAndEstado(String cbu, EstadoConciliacion estado);
    List<MovimientoBancario> findByFechaAndImporteAndEsCredito(
            LocalDate fecha, BigDecimal importe, Boolean esCredito);
}