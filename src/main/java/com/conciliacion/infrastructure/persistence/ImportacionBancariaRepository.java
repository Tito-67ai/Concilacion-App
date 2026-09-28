package com.conciliacion.infrastructure.persistence;

import com.conciliacion.domain.model.ImportacionBancaria;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Historial de corridas de importacion. Sin esto, cuando una API trae 400 filas y 3
 * estan raras, no hay forma de saber que se pidio, cuando, ni cuantas entraron.
 */
public interface ImportacionBancariaRepository extends JpaRepository<ImportacionBancaria, Long> {

    List<ImportacionBancaria> findByCuentaBancariaIdOrderByIniciadaEnDesc(Long cuentaBancariaId);

    List<ImportacionBancaria> findAllByOrderByIniciadaEnDesc();
}
