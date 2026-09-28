package com.conciliacion.application.banco;

import com.conciliacion.domain.model.CuentaBancaria;
import com.conciliacion.domain.model.ImportacionBancaria;
import com.conciliacion.domain.model.MovimientoBancario;
import com.conciliacion.infrastructure.persistence.CuentaBancariaRepository;
import com.conciliacion.infrastructure.persistence.ImportacionBancariaRepository;
import com.conciliacion.infrastructure.persistence.MovimientoBancarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Unico camino por el que entran movimientos bancarios a la base. tanto el CSV que
 * sube el usuario, como la API de Galicia, como la de Xubio pasan por aca.
 *
 * Concentrarlo aca es lo que permite que un banco nuevo no reimprima deduplicacion:
 *ExtractorDemo lo usa, lo va a usar el CSV, y lo va a usar el conector de cada banco.
 *
 * DEDUPLICACION
 * Las APIs bancarias piden ventanas de fechas y siempre hay que pedir un margen hacia
 * atras, porque un pago con valor de hoy se puede confirmar mañana. Ese margen
 * significa que cada sincronizacion vuelve a traer filas ya bajadas. Por eso la clave
 * es (cuenta, comprobante):
 *
 *   - si la fila ya existe en esa cuenta, no se inserta y se cuenta como duplicada;
 *   - si la fila aparece dos veces EN EL MISMO lote, se descarta la segunda;
 *   - si el comprobante es null (carga manual, o un banco que no lo manda), NO se
 *     deduplica. Es el precio de conectar una fuente sin ID de transaccion.
 */
@Service
public class BancoIngestionService {

    private final MovimientoBancarioRepository bancoRepo;
    private final CuentaBancariaRepository cuentaBancariaRepo;
    private final ImportacionBancariaRepository importacionRepo;
    private final ExtractorBancoRegistry registry;

    public BancoIngestionService(MovimientoBancarioRepository bancoRepo,
                                 CuentaBancariaRepository cuentaBancariaRepo,
                                 ImportacionBancariaRepository importacionRepo,
                                 ExtractorBancoRegistry registry) {
        this.bancoRepo = bancoRepo;
        this.cuentaBancariaRepo = cuentaBancariaRepo;
        this.importacionRepo = importacionRepo;
        this.registry = registry;
    }

    /** Corre un extractor de los que saben falar con un sistema externo. */
    @Transactional
    public ImportacionBancaria importar(String codigoExtractor, Long cuentaBancariaId,
                                        RangoFechas rango) {
        ExtractorBancario extractor = registry.obtener(codigoExtractor);
        if (!extractor.puedeEjecutarseSolo()) {
            throw new IllegalArgumentException(
                    "El extractor " + codigoExtractor + " necesita un archivo: usar /importaciones/archivo");
        }
        CuentaBancaria cuenta = cuentaRequerida(cuentaBancariaId);
        return persistir(cuenta, extractor, rango,
                extractor.extraer(cuenta, rango));
    }

    /** Corre un extractor con un archivo que sube el usuario. */
    @Transactional
    public ImportacionBancaria importarArchivo(String codigoExtractor, Long cuentaBancariaId,
                                               RangoFechas rango, byte[] contenido) {
        ExtractorBancario extractor = registry.obtener(codigoExtractor);
        if (!extractor.aceptaArchivo()) {
            throw new IllegalArgumentException("El extractor " + codigoExtractor + " no lee archivos");
        }
        CuentaBancaria cuenta = cuentaRequerida(cuentaBancariaId);
        return persistir(cuenta, extractor, rango,
                extractor.extraerDeArchivo(cuenta, rango, contenido));
    }

    private ImportacionBancaria persistir(CuentaBancaria cuenta, ExtractorBancario extractor,
                                         RangoFechas rango, List<MovimientoBancoCrudo> crudos) {
        // La corrida se guarda PRIMERO, sin conteos, para que las filas tengan una FK
        // que apuntar. Si la fuente explota (auth, 429, timeout), esto no llego a
        // correr, y no queda una corrida "exitosa" de una importacion que no ocurrio.
        ImportacionBancaria corrida = importacionRepo.save(
                new ImportacionBancaria(cuenta, extractor.origen(), extractor.codigo(),
                        rango.desde(), rango.hasta()));

        List<MovimientoBancoCrudo> validos = soloValidos(crudos);

        // Deduplicar DENTRO del lote: un CSV con la misma fila dos veces, o un banco
        // que devuelve la misma operacion en dos paginas, no debe entrar dos veces.
        Set<String> vistos = new LinkedHashSet<>();
        List<MovimientoBancoCrudo> unicos = new ArrayList<>();
        for (MovimientoBancoCrudo c : validos) {
            if (c.comprobante() == null) { unicos.add(c); continue; }
            if (vistos.add(c.comprobante())) unicos.add(c);
        }

        // Deduplicar CONTRA lo que ya esta en la base.
        Set<String> yaPresentes = comprobantesYaImportados(cuenta, unicos);

        List<MovimientoBancario> nuevas = new ArrayList<>();
        for (MovimientoBancoCrudo c : unicos) {
            if (c.comprobante() != null && yaPresentes.contains(c.comprobante())) continue;
            MovimientoBancario m = new MovimientoBancario(cuenta, c.fecha(), c.fechaOperacion(),
                    c.detalle(), c.importe(), c.esCredito(), c.comprobante(), c.saldo(),
                    extractor.origen());
            m.setImportacion(corrida);
            nuevas.add(m);
        }
        bancoRepo.saveAll(nuevas);

        cuenta.marcarImportadaAhora();
        cuentaBancariaRepo.save(cuenta);

        int duplicados = validos.size() - nuevas.size();
        corrida.cerrar(validos.size(), nuevas.size(), duplicados, null);
        importacionRepo.save(corrida);
        return corrida;
    }

    /**
     * Filtra lo que no tiene los minimos para persistirse. Un CSV con una fila
     * rota no puede abortar toda la importacion.
     */
    private List<MovimientoBancoCrudo> soloValidos(List<MovimientoBancoCrudo> crudos) {
        List<MovimientoBancoCrudo> validos = new ArrayList<>();
        for (MovimientoBancoCrudo c : crudos) {
            if (c == null || c.fecha() == null || c.importe() == null || c.esCredito() == null) continue;
            validos.add(c);
        }
        return validos;
    }

    private Set<String> comprobantesYaImportados(CuentaBancaria cuenta, List<MovimientoBancoCrudo> lote) {
        List<String> ids = new ArrayList<>();
        for (MovimientoBancoCrudo c : lote) {
            if (c.comprobante() != null) ids.add(c.comprobante());
        }
        if (ids.isEmpty()) return Set.of();
        return new LinkedHashSet<>(bancoRepo.findComprobantesExistentes(cuenta.getId(), ids));
    }

    private CuentaBancaria cuentaRequerida(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Falta cuentaBancariaId: sin cuenta no hay a que colgar el movimiento");
        }
        return cuentaBancariaRepo.findById(id).orElseThrow(
                () -> new IllegalArgumentException("No existe la cuenta bancaria " + id));
    }
}
