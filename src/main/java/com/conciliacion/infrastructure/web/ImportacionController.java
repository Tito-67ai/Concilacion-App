package com.conciliacion.infrastructure.web;

import com.conciliacion.application.banco.ExtractorBancario;
import com.conciliacion.application.banco.ExtractorDesconocidoException;
import com.conciliacion.application.banco.RangoFechas;
import com.conciliacion.application.banco.BancoIngestionService;
import com.conciliacion.application.banco.ExtractorBancoRegistry;
import com.conciliacion.domain.model.ImportacionBancaria;
import com.conciliacion.infrastructure.persistence.ImportacionBancariaRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * Importacion bancaria y su historial.
 *
 * Vive en /api/importaciones y no dentro de /api/conciliaciones a proposito: importar
 * no es conciliar. Conciliar produce un Conciliacion; importar produce movimientos y
 * deja una corrida registrada. Son dos cosas distintas, y el historial de "que
 * bajamos" va a existir al margen del de "que conciliamos".
 *
 * Cuando se conecte una API real, esta clase no cambia. Lo unico que hay que hacer es
 * escribir el extractor del banco con @Component: aparece solo en el registro, en el
 * listado que arma el boton Importar, y en la deduplicacion.
 */
@RestController
@RequestMapping("/api/importaciones")
public class ImportacionController {

    private final BancoIngestionService ingesta;
    private final ExtractorBancoRegistry registry;
    private final ImportacionBancariaRepository importacionRepo;

    public ImportacionController(BancoIngestionService ingesta,
                                 ExtractorBancoRegistry registry,
                                 ImportacionBancariaRepository importacionRepo) {
        this.ingesta = ingesta;
        this.registry = registry;
        this.importacionRepo = importacionRepo;
    }

    /** Que fuentes hay disponibles. Es lo que la UI lista en el boton Importar. */
    @GetMapping("/extractores")
    public List<InfoExtractor> extractores() {
        return registry.disponibles().stream()
                .map(InfoExtractor::de)
                .toList();
    }

    /**
     * Corre una fuente que se conecta sola. Hoy no hay ninguna: Excel y PDF necesitan
     * archivo. El endpoint queda porque es el que va a usar la API de un banco, que se
     * llama sola y no tiene archivo que subir.
     */
    @PostMapping("/extractores/{codigo}")
    public ImportacionBancaria importar(
            @PathVariable String codigo,
            @RequestParam Long cuentaBancariaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return ingesta.importar(codigo, cuentaBancariaId, new RangoFechas(desde, hasta));
    }

    /** Sube un archivo que baja el usuario del home banking (Excel o PDF). */
    @PostMapping("/archivo")
    public ImportacionBancaria importarArchivo(
            @RequestParam("archivo") MultipartFile archivo,
            @RequestParam String extractor,
            @RequestParam Long cuentaBancariaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        byte[] contenido;
        try {
            contenido = archivo.getBytes();
        } catch (IOException e) {
            // El archivo se corto o fallo la lectura. Es problema del cliente, no 500.
            throw new ArchivoInvalidoException("No se pudo leer el archivo: " + e.getMessage());
        }
        return ingesta.importarArchivo(extractor, cuentaBancariaId,
                new RangoFechas(desde, hasta), contenido);
    }

    /** Historial de corridas, opcionalmente de una sola cuenta. */
    @GetMapping
    public List<ImportacionBancaria> historial(
            @RequestParam(required = false) Long cuentaBancariaId) {
        if (cuentaBancariaId == null) {
            return importacionRepo.findAllByOrderByIniciadaEnDesc();
        }
        return importacionRepo.findByCuentaBancariaIdOrderByIniciadaEnDesc(cuentaBancariaId);
    }

    /**
     * Lo que la UI necesita mostrar para elegir una fuente.
     *
     * `extensiones` viaja con el resto a proposito: el menu Importar arma el
     * `accept` del selector de archivos con esto. Si la lista estuviera escrita en
     * el HTML del frontend, agregar un extractor obligaria a acordarse de tocar la
     * pantalla, y olvidar esa linea deja el boton ofreciendo archivos que el
     * backend va a rechazar.
     */
    public record InfoExtractor(String codigo, String descripcion, String origen,
                                boolean aceptaArchivo, boolean puedeEjecutarseSolo,
                                List<String> extensiones) {
        static InfoExtractor de(ExtractorBancario e) {
            return new InfoExtractor(e.codigo(), e.descripcion(), e.origen().name(),
                    e.aceptaArchivo(), e.puedeEjecutarseSolo(), e.extensionesAceptadas());
        }
    }

    /** El archivo vino vacio o corrupto. 422: el pedido se entiende, el contenido no. */
    public static class ArchivoInvalidoException extends RuntimeException {
        public ArchivoInvalidoException(String m) { super(m); }
    }
}
