package com.conciliacion.infrastructure.persistence;

import com.conciliacion.domain.model.EstadoConciliacion;
import com.conciliacion.domain.model.MovimientoBancario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
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

    /**
     * De los comprobantes dados, cuales YA estan cargados en esa cuenta. Es la
     * consulta que hace idempotente una reimportacion: la ingesta pasa por aca antes
     * de insertar y descarta los que ya estaban.
     *
     * Proyecta solo el comprobante, no la entidad entera: con 400 movimientos por
     * pagina trayendo 5 FKs EAGER cada uno, traer la entidad entera deja de ser una
     * consulta y pasa a ser una descarga.
     */
    @Query("""
            select m.comprobante from MovimientoBancario m
            where m.cuentaBancaria.id = :cuentaBancariaId
              and m.comprobante in :comprobantes
            """)
    List<String> findComprobantesExistentes(
            @Param("cuentaBancariaId") Long cuentaBancariaId,
            @Param("comprobantes") Collection<String> comprobantes);
}
