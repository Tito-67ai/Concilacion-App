package com.conciliacion.infrastructure.config;

import com.conciliacion.domain.model.EstadoConciliacion;
import com.conciliacion.domain.model.MovimientoBancario;
import com.conciliacion.domain.model.MovimientoContable;
import com.conciliacion.domain.model.OrigenMovimiento;
import com.conciliacion.infrastructure.persistence.MovimientoBancarioRepository;
import com.conciliacion.infrastructure.persistence.MovimientoContableRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Siembra el demo con datos de ejemplo (una sola vez).
 * Deja par de verdad: banco (20/09, importe 152000.00, credito) que tiene su par contable EXACTO
 * por (fecha, importe, esCredito), para que el motor AUTOCONCILIE solito el primer POST.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final MovimientoBancarioRepository bancoRepo;
    private final MovimientoContableRepository contableRepo;

    public DataSeeder(MovimientoBancarioRepository bancoRepo,
                      MovimientoContableRepository contableRepo) {
        this.bancoRepo = bancoRepo;
        this.contableRepo = contableRepo;
    }

    @Override
    public void run(String... args) {
        if (bancoRepo.count() > 0) return;

        bancoRepo.save(new MovimientoBancario(
                "0000003100000003079083", LocalDate.of(2026, 9, 20),
                "VENTA MOSTRADOR", new BigDecimal("152000.00"), Boolean.TRUE));

        bancoRepo.save(new MovimientoBancario(
                "0000003100000003079083", LocalDate.of(2026, 9, 19),
                "PAGO PROVEEDOR", new BigDecimal("48000.00"), Boolean.FALSE));

        contableRepo.save(new MovimientoContable(
                "XUB-2026-091", LocalDate.of(2026, 9, 20),
                "VENTA MOSTRADOR FACT A", new BigDecimal("152000.00"), Boolean.TRUE,
                OrigenMovimiento.XUBIO));

        System.out.println("== SEED DEMO LISTO ==");
        System.out.println("  banco 1 (20/09, +152000.00, credito) tiene par EXACTO -> el POST /autoconciliar te lo concilia solo");
        System.out.println("  banco 2 (19/09, -48000.00, debito) sin par -> queda PENDIENTE pa' que decidas vos");
    }
}