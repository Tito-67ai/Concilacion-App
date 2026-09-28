package com.conciliacion.infrastructure.persistence;

import com.conciliacion.domain.model.Conciliacion;
import com.conciliacion.domain.model.EstadoConciliacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Filtros opcionales: null = "no filtrar por este campo".
 *
 * OJO con el rango de fechas: se compara contra `fecha`, que en Conciliacion es la
 * FECHA BANCARIA (la del extracto), no la contable. Son distintas y por eso el filtro
 * tiene que ser explicito en la UI.
 */
public interface ConciliacionRepository extends JpaRepository<Conciliacion, Long> {

    @Query("""
            select c from Conciliacion c
            where (:cuentaBancariaId is null or c.cuentaBancaria.id = :cuentaBancariaId)
              and (:cuentaContableId  is null or c.cuentaContable.id  = :cuentaContableId)
              and (:circuitoId        is null or c.circuito.id        = :circuitoId)
              and (:desde             is null or c.fecha >= :desde)
              and (:hasta             is null or c.fecha <= :hasta)
              and (:estado            is null or c.estado = :estado)
            order by c.fecha desc, c.id desc
            """)
    List<Conciliacion> buscarConFiltros(
            @Param("cuentaBancariaId") Long cuentaBancariaId,
            @Param("cuentaContableId") Long cuentaContableId,
            @Param("circuitoId") Long circuitoId,
            @Param("desde") LocalDate desde,
            @Param("hasta") LocalDate hasta,
            @Param("estado") EstadoConciliacion estado);
}
