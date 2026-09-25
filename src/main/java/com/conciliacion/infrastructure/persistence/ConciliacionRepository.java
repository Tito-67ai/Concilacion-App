package com.conciliacion.infrastructure.persistence;

import com.conciliacion.domain.model.Conciliacion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConciliacionRepository extends JpaRepository<Conciliacion, Long> {
}