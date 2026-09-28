package com.conciliacion.infrastructure.persistence;

import com.conciliacion.domain.model.CircuitoContable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Alimenta el desplegable "Circuito contable" del filtro. */
public interface CircuitoContableRepository extends JpaRepository<CircuitoContable, Long> {
    List<CircuitoContable> findAllByOrderByNombreAsc();
}
