package com.conciliacion.infrastructure.persistence;

import com.conciliacion.domain.model.CuentaBancaria;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Alimenta el desplegable "Cuenta bancaria" del filtro. */
public interface CuentaBancariaRepository extends JpaRepository<CuentaBancaria, Long> {
    List<CuentaBancaria> findAllByOrderByNombreAsc();
}
