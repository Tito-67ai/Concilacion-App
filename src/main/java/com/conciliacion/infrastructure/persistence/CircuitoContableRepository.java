package com.conciliacion.infrastructure.persistence;

import com.conciliacion.domain.model.CircuitoContable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Alimenta el desplegable "Circuito contable" del filtro. */
public interface CircuitoContableRepository extends JpaRepository<CircuitoContable, Long> {
    List<CircuitoContable> findAllByOrderByNombreAsc();

    /** Clave de la sincronizacion con Xubio. Ver CuentaBancariaRepository. */
    Optional<CircuitoContable> findByXubioId(String xubioId);
}
