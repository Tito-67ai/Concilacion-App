package com.conciliacion.infrastructure.persistence;

import com.conciliacion.domain.model.CuentaContable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Alimenta el desplegable "Cuenta contable" del filtro. */
public interface CuentaContableRepository extends JpaRepository<CuentaContable, Long> {
    List<CuentaContable> findAllByOrderByCodigoAsc();
}
