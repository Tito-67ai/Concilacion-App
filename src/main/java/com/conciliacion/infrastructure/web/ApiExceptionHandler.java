package com.conciliacion.infrastructure.web;

import com.conciliacion.application.banco.ExtractorDesconocidoException;
import com.conciliacion.application.catalogo.CatalogoNoDisponibleException;
import com.conciliacion.infrastructure.banco.ExtractorExcelBanco;
import com.conciliacion.infrastructure.banco.ExtractorPdfBanco;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Traduccion de excepciones a codigos HTTP.
 *
 * Antes no habia nada de esto, asi que pedir un extractor inexistente devolvia un 500
 * con el stack trace entero: el cliente no tenia forma de saber si estaba escribiendo
 * mal el codigo o si la app estaba rota. Y lo que mas importa para una app de
 * conciliacion, los errores de integridad (dos usuarios conciliando la misma fila,
 * dos importaciones del mismo rango) salian como 500, cuando en realidad son un 409
 * que el usuario puede reintentar.
 *
 * El cuerpo es siempre {error, detalle} para que el frontend pueda mostrarlo sin
 * parsear a ciegas.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** El codigo de extractor no existe. 404: se pidio algo que no esta. */
    @ExceptionHandler(ExtractorDesconocidoException.class)
    public ResponseEntity<Map<String, Object>> extractorDesconocido(ExtractorDesconocidoException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "EXTRACTOR_DESCONOCIDO",
                "detalle", e.getMessage(),
                "disponibles", e.getCodigosDisponibles()));
    }

    /** Request invalido: falta una cuenta, fechas al reves, extractor que no va. 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> requestInvalido(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "REQUEST_INVALIDO",
                "detalle", e.getMessage() == null ? "Sin detalle" : e.getMessage()));
    }

    /**
     * Conflicto de datos: dos importaciones del mismo rango a la vez, o dos usuarios
     * tocando la misma fila. Es 409 y NO 500 porque el usuario puede reintentar: el
     * estado del sistema es correcto, solo hubo contencion.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> conflicto(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "CONFLICTO",
                "detalle", "La operacion choco con un cambio concurrente. Reintentar."));
    }

    /** El archivo se corto o no se pudo leer. 422: el pedido se entiende, el contenido no. */
    @ExceptionHandler(ImportacionController.ArchivoInvalidoException.class)
    public ResponseEntity<Map<String, Object>> archivoInvalido(RuntimeException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "error", "ARCHIVO_INVALIDO",
                "detalle", e.getMessage()));
    }

    /**
     * El archivo llego entero pero su contenido no se puede trabajar: un .xlsx que
     * Excel no puede abrir, un PDF cifrado, un PDF escaneado sin capa de texto.
     *
     * 422 y no 400: el pedido esta bien formado y la cuenta y el rango son validos,
     * lo que no sirve es lo que el usuario subio. Con 400 el frontend suele mostrar
     * "revisá los datos" y el usuario revisa los filtros, que estan perfecto. Con
     * 422 el mensaje puede ser el del archivo, que es lo que hay que arreglar.
     *
     * Van los dos extractores de archivo juntos y no por separado porque el cliente
     * los trata igual: no reintenta, muestra el texto y le deja subir otro.
     */
    @ExceptionHandler({
            ExtractorExcelBanco.NoSePudoAbrirException.class,
            ExtractorPdfBanco.NoSePudoAbrirException.class,
            ExtractorPdfBanco.PdfSinCapaDeTextoException.class})
    public ResponseEntity<Map<String, Object>> archivoNoUtilizable(RuntimeException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "error", "ARCHIVO_NO_UTILIZABLE",
                "detalle", e.getMessage()));
    }

    /**
     * No se pudieron leer los catalogos de Xubio.
     *
     * 503 y no 500 ni 200 con lista vacia.
     *
     * - No 200 con vacio, porque en la pantalla un desplegable sin opciones y un
     *   desplegable que no se pudo cargar se ven EXACTAMENTE igual, y significan
     *   cosas opuestas para el usuario: "no tenes cuentas cargadas en Xubio"
     *   contra "no pudimos preguntarle a Xubio". Con la lista vacia el usuario
     *   se va a Xubio a revisar algo que esta perfecto.
     * - No 500, porque aca no se rompio nada: el pedido se hizo bien y el
     *   servicio de afuera no contesto. Un 500 le dice al cliente que la app esta
     *   rota y no tiene sentido reintentar.
     *
     * El `reintentable` viaja en el cuerpo para que el frontend pueda ofrecer
     * "reintentar" solo cuando sirve: con credenciales malas el boton de
     * reintentar no hace nada util, porque el mismo pedido va a fallar igual.
     */
    @ExceptionHandler(CatalogoNoDisponibleException.class)
    public ResponseEntity<Map<String, Object>> catalogoNoDisponible(CatalogoNoDisponibleException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "CATALOGO_NO_DISPONIBLE",
                "detalle", e.getMessage() == null ? "No se pudieron leer los catalogos." : e.getMessage(),
                "reintentable", e.esReintentable()));
    }
}
