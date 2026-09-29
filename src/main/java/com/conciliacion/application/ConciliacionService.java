package com.conciliacion.application;

import com.conciliacion.application.catalogo.CatalogoNoDisponibleException;
import com.conciliacion.application.catalogo.EstadoCatalogos;
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
    private final EstadoCatalogos estadoCatalogos;

    public ConciliacionService(MovimientoBancarioRepository bancoRepo,
                               MovimientoContableRepository contableRepo,
                               ConciliacionRepository conciliacionRepo,
                               CuentaBancariaRepository cuentaBancariaRepo,
                               CuentaContableRepository cuentaContableRepo,
                               CircuitoContableRepository circuitoRepo,
                               EstadoCatalogos estadoCatalogos) {
        this.bancoRepo = bancoRepo;
        this.contableRepo = contableRepo;
        this.conciliacionRepo = conciliacionRepo;
        this.cuentaBancariaRepo = cuentaBancariaRepo;
        this.cuentaContableRepo = cuentaContableRepo;
        this.circuitoRepo = circuitoRepo;
        this.estadoCatalogos = estadoCatalogos;
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
    public List<MovimientoContable> pendientesContables(FiltroConciliacion filtro) {
        return contableRepo.buscarPendientesConFiltros(EstadoConciliacion.PENDIENTE,
                filtro.cuentaContableId(), filtro.circuitoId(), filtro.desde(), filtro.hasta());
    }

    @Transactional(readOnly = true)
    public OpcionesFiltro opciones() {
        // Si hay una fuente de catalogos prendida y no se pudo leer, NO se sirven
        // las opciones de la base. Se tiran 503 con el motivo.
        //
        // ── POR QUE ACA Y NO EN EL CONTROLADOR ──────────────────────────────────
        //
        // Porque la decision "estos datos son de fiar" es del dominio, no del
        // transporte. El mismo pedido, llegue por REST o por lo que sea despues,
        // tiene que dar el mismo resultado.
        //
        // ── POR QUE NO SE SIRVEN IGUAL ──────────────────────────────────────────
        //
        // Porque en la base puede haber cuentas sembradas que NO son de la
        // empresa. Servirlas con un 200 es la peor version posible del fallo: el
        // desplegable se ve lleno y correcto, el usuario elige "Cuenta Corriente /
        // Banco Galicia" creyendo que es real, y no hay ni un signo de que algo
        // este mal. Un error visible se arregla mirando; eso no.
        if (estadoCatalogos.hayProblema()) {
            // El `reintentable` sale del estado y no se hardcodea: el cliente ya
            // decidio si reintentar sirve cuando lanzo la excepcion. Decir siempre
            // que si hace que un boton de "reintentar" con credenciales malas
            // prometa algo que no va a pasar.
            throw new CatalogoNoDisponibleException(estadoCatalogos.motivo(),
                    estadoCatalogos.reintentable());
        }
        return new OpcionesFiltro(cuentaBancariaRepo.findAllByOrderByNombreAsc(),
                cuentaContableRepo.findAllByOrderByCodigoAsc(),
                circuitoRepo.findAllByOrderByNombreAsc());
    }

    /**
     * Conciliacion manual: la persona elige una fila de cada panel.
     *
     * A diferencia del automatico, NO se busca por fecha e importe: justamente para
     * eso esta la pantalla, para los casos donde los dos lados dicen cosas distintas
     * (un chequeque con fecha valor distinta, un agrupamiento de cuotas, una
     * diferencia de redondeo). Si exigiera coincidencia exacta seria el mismo
     * endpoint que el automatico y la pantalla no serviria de nada.
     *
     * Lo que SI se valida es que los dos lados sean del mismo signo. Un credito
     * conciliado contra un debito no es un caso raro de negocio: es agarrar la fila
     * del panel equivocado, y dejarlo pasar contamina el historial sin que nadie lo
     * note hasta el cierre. Se rechaza con 409 y un detalle que lo dice.
     */
    @Transactional
    public ResultadoConciliacion conciliarManualmente(Long idBanco, Long idContable) {
        MovimientoBancario banco = bancoRepo.findById(idBanco).orElse(null);
        MovimientoContable contable = contableRepo.findById(idContable).orElse(null);
        if (banco == null || contable == null) {
            return ResultadoConciliacion.noExiste();
        }
        if (!EstadoConciliacion.PENDIENTE.equals(banco.getEstado())
                || !EstadoConciliacion.PENDIENTE.equals(contable.getEstado())) {
            return ResultadoConciliacion.sinPar();
        }
        if (!Objects.equals(banco.getEsCredito(), contable.getEsCredito())) {
            return ResultadoConciliacion.signoIncompatible(
                    "El movimiento bancario es un " + tipo(banco.getEsCredito())
                            + " y el contable es un " + tipo(contable.getEsCredito())
                            + ". No se puede conciliar un credito contra un debito.");
        }

        banco.setEstado(EstadoConciliacion.CONCILIADO);
        contable.setEstado(EstadoConciliacion.CONCILIADO);
        bancoRepo.save(banco);
        contableRepo.save(contable);

        Conciliacion guardada = conciliacionRepo.save(
                Conciliacion.registrar(EstadoConciliacion.CONCILIADO, banco, contable));
        return ResultadoConciliacion.ok(guardada);
    }

    private static String tipo(Boolean esCredito) {
        return Boolean.TRUE.equals(esCredito) ? "credito" : "debito";
    }

    @Transactional
    public ResultadoConciliacion autoconciliar(Long idBanco) {
        MovimientoBancario bco = bancoRepo.findById(idBanco).orElse(null);
        if (bco == null) {
            return ResultadoConciliacion.noExiste();
        }
        if (!EstadoConciliacion.PENDIENTE.equals(bco.getEstado())) {
            return ResultadoConciliacion.sinPar();
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
            return ResultadoConciliacion.sinPar();
        }

        MovimientoContable par = pares.get(0);
        par.setEstado(EstadoConciliacion.CONCILIADO);
        bco.setEstado(EstadoConciliacion.CONCILIADO);

        Conciliacion guardada = conciliacionRepo.save(
                Conciliacion.registrar(EstadoConciliacion.CONCILIADO, bco, par));
        bancoRepo.save(bco);
        contableRepo.save(par);
        return ResultadoConciliacion.ok(guardada);
    }

    /** Descarta un movimiento que no va a tener contrapartida (comision del banco, etc).
     *  Sin esto, esa fila queda en la cola para siempre. */
    @Transactional
    public ResultadoConciliacion descartar(Long idBanco) {
        MovimientoBancario bco = bancoRepo.findById(idBanco).orElse(null);
        if (bco == null) {
            return ResultadoConciliacion.noExiste();
        }
        if (!EstadoConciliacion.PENDIENTE.equals(bco.getEstado())) {
            return ResultadoConciliacion.sinPar();
        }
        bco.setEstado(EstadoConciliacion.DESCARTADO);
        bancoRepo.save(bco);
        return new ResultadoConciliacion(ResultadoConciliacion.Estado.OK, null);
    }
}
