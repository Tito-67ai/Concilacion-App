package com.conciliacion.infrastructure.persistence;

import com.conciliacion.domain.model.EstadoConciliacion;
import com.conciliacion.domain.model.MovimientoBancario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface MovimientoBancarioRepository extends JpaRepository<MovimientoBancario, Long> {

    /**
     * Antes esto era `findAll()` + filtro en memoria en el service. Con volumen real
     * eso es un escaneo de tabla completa por request. Ahora la query trae solo lo
     * que pidas, y null = no filtrar.
     */
    @Query("""
            select m from MovimientoBancario m
            where m.estado = :estado
              and (:cuentaBancariaId is null or m.cuentaBancaria.id = :cuentaBancariaId)
              and (:desde is null or m.fecha >= :desde)
              and (:hasta is null or m.fecha <= :hasta)
            order by m.fecha desc, m.id desc
            """)
    List<MovimientoBancario> buscarConFiltros(
            @Param("estado") EstadoConciliacion estado,
            @Param("cuentaBancariaId") Long cuentaBancariaId,
            @Param("desde") LocalDate desde,
            @Param("hasta") LocalDate hasta);

    List<MovimientoBancario> findByFechaAndImporteAndEsCredito(
            LocalDate fecha, BigDecimal importe, Boolean esCredito);
}
