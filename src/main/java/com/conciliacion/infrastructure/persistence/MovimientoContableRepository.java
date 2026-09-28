package com.conciliacion.infrastructure.persistence;

import com.conciliacion.domain.model.EstadoConciliacion;
import com.conciliacion.domain.model.MovimientoContable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * El panel derecho de la pantalla de matching: los movimientos contables que todavia
 * no fueron conciliados contra nada.
 *
 * Filtra por cuenta contable, circuito y fechas, NO por cuenta bancaria: en este lado
 * todavia no hay cuenta bancaria, porque la cuenta viene del movimiento del otro
 * panel y se conoce recien cuando se elige la pareja. Por eso la barra de filtros de
 * esa pantalla tiene los dos grupos de campos: los del panel izquierdo y los del
 * derecho.
 */
public interface MovimientoContableRepository extends JpaRepository<MovimientoContable, Long> {

    List<MovimientoContable> findByEstado(EstadoConciliacion estado);

    @Query("""
            select m from MovimientoContable m
            where m.estado = :estado
              and (:cuentaContableId is null or m.cuentaContable.id = :cuentaContableId)
              and (:circuitoId       is null or m.circuito.id       = :circuitoId)
              and (:desde            is null or m.fecha >= :desde)
              and (:hasta            is null or m.fecha <= :hasta)
            order by m.fecha desc, m.id desc
            """)
    List<MovimientoContable> buscarPendientesConFiltros(
            @Param("estado") EstadoConciliacion estado,
            @Param("cuentaContableId") Long cuentaContableId,
            @Param("circuitoId") Long circuitoId,
            @Param("desde") LocalDate desde,
            @Param("hasta") LocalDate hasta);
}
