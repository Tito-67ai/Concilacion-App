package com.conciliacion.infrastructure.persistence;

import com.conciliacion.domain.model.CuentaBancaria;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Alimenta el desplegable "Cuenta bancaria" del filtro. */
public interface CuentaBancariaRepository extends JpaRepository<CuentaBancaria, Long> {
    List<CuentaBancaria> findAllByOrderByNombreAsc();

    /**
     * Busca por el id de Xubio. Es la clave de la sincronizacion: sin este metodo
     * no hay forma de distinguir "esta cuenta ya la bajó antes" de "esta cuenta
     * es nueva", y cada sincronizacion insertaria el catalogo entero de nuevo.
     */
    Optional<CuentaBancaria> findByXubioId(String xubioId);
}
