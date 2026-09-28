package com.conciliacion.application;

import com.conciliacion.domain.model.CircuitoContable;
import com.conciliacion.domain.model.Conciliacion;
import com.conciliacion.domain.model.CuentaBancaria;
import com.conciliacion.domain.model.CuentaContable;
import com.conciliacion.domain.model.EstadoConciliacion;
import com.conciliacion.domain.model.MovimientoBancario;
import com.conciliacion.domain.model.MovimientoContable;
import com.conciliacion.infrastructure.persistence.CircuitoContableRepository;
import com.conciliacion.infrastructure.persistence.ConciliacionRepository;
import com.conciliacion.infrastructure.persistence.CuentaBancariaRepository;
import com.conciliacion.infrastructure.persistence.CuentaContableRepository;
import com.conciliacion.infrastructure.persistence.MovimientoBancarioRepository;
import com.conciliacion.infrastructure.persistence.MovimientoContableRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class ConciliacionService {

    private final MovimientoBancarioRepository bancoRepo;
    private final MovimientoContableRepository contableRepo;
    private final ConciliacionRepository conciliacionRepo;
    private final CuentaBancariaRepository cuentaBancariaRepo;
    private final CuentaContableRepository cuentaContableRepo;
    private final CircuitoContableRepository circuitoRepo;

    public ConciliacionService(MovimientoBancarioRepository bancoRepo,
                               MovimientoContableRepository contableRepo,
                               ConciliacionRepository conciliacionRepo,
                               CuentaBancariaRepository cuentaBancariaRepo,
                               CuentaContableRepository cuentaContableRepo,
                               CircuitoContableRepository circuitoRepo) {
        this.bancoRepo = bancoRepo;
        this.contableRepo = contableRepo;
        this.conciliacionRepo = conciliacionRepo;
        this.cuentaBancariaRepo = cuentaBancariaRepo;
        this.cuentaContableRepo = cuentaContableRepo;
        this.circuitoRepo = circuitoRepo;
    }

    /**
     * Antes era `findAll()` + filtro en memoria. Ahora la query trae solo lo pedido.
     * Nota: un pendiente todavia no tiene contrapartida contable, asi que aqui NO
     * aplican los filtros de cuenta contable ni circuito.
     */
    @Transactional(readOnly = true)
    public List<MovimientoBancario> pendientes(FiltroConciliacion filtro) {
        return bancoRepo.buscarConFiltros(EstadoConciliacion.PENDIENTE,
                filtro.cuentaBancariaId(), filtro.desde(), filtro.hasta());
    }

    @Transactional(readOnly = true)
    public List<Conciliacion> conciliaciones(FiltroConciliacion filtro) {
        return conciliacionRepo.buscarConFiltros(filtro.cuentaBancariaId(), filtro.cuentaContableId(),
                filtro.circuitoId(), filtro.desde(), filtro.hasta(), filtro.estado());
    }

    @Transactional(readOnly = true)
    public OpcionesFiltro opciones() {
        return new OpcionesFiltro(cuentaBancariaRepo.findAllByOrderByNombreAsc(),
                cuentaContableRepo.findAllByOrderByCodigoAsc(),
                circuitoRepo.findAllByOrderByNombreAsc());
    }

    @Transactional
    public ResultadoConciliacion autoconciliar(Long idBanco) {
        MovimientoBancario bco = bancoRepo.findById(idBanco).orElse(null);
        if (bco == null) {
            return new ResultadoConciliacion(ResultadoConciliacion.Estado.NO_EXISTE, null);
        }
        if (!EstadoConciliacion.PENDIENTE.equals(bco.getEstado())) {
            return new ResultadoConciliacion(ResultadoConciliacion.Estado.SIN_PAR, null);
        }

        List<MovimientoContable> pares = new ArrayList<>();
        for (MovimientoContable c : contableRepo.findByEstado(EstadoConciliacion.PENDIENTE)) {
            if (!Objects.equals(c.getFecha(), bco.getFecha())) continue;
            // compareTo y NO equals: BigDecimal.equals() compara tambien la escala, asi
            // que 152000.00.equals(152000.0) da false y el match seeria al viento.
            if (c.getImporte().compareTo(bco.getImporte()) != 0) continue;
            if (!Objects.equals(c.getEsCredito(), bco.getEsCredito())) continue;
            pares.add(c);
        }
        // Si hay mas de un candidato no se decide solo: eso es trabajo de la pantalla
        // de matching, no del automatico.
        if (pares.size() != 1) {
            return new ResultadoConciliacion(ResultadoConciliacion.Estado.SIN_PAR, null);
        }

        MovimientoContable par = pares.get(0);
        par.setEstado(EstadoConciliacion.CONCILIADO);
        bco.setEstado(EstadoConciliacion.CONCILIADO);

        Conciliacion guardada = conciliacionRepo.save(
                Conciliacion.registrar(EstadoConciliacion.CONCILIADO, bco, par));
        bancoRepo.save(bco);
        contableRepo.save(par);
        return new ResultadoConciliacion(ResultadoConciliacion.Estado.OK, guardada);
    }

    /** Descarta un movimiento que no va a tener contrapartida (comision del banco, etc).
     *  Sin esto, esa fila queda en la cola para siempre. */
    @Transactional
    public ResultadoConciliacion descartar(Long idBanco) {
        MovimientoBancario bco = bancoRepo.findById(idBanco).orElse(null);
        if (bco == null) {
            return new ResultadoConciliacion(ResultadoConciliacion.Estado.NO_EXISTE, null);
        }
        if (!EstadoConciliacion.PENDIENTE.equals(bco.getEstado())) {
            return new ResultadoConciliacion(ResultadoConciliacion.Estado.SIN_PAR, null);
        }
        bco.setEstado(EstadoConciliacion.DESCARTADO);
        bancoRepo.save(bco);
        return new ResultadoConciliacion(ResultadoConciliacion.Estado.OK, null);
    }
}
