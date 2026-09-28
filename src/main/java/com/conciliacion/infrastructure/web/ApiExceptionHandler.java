package com.conciliacion.infrastructure.web;

import com.conciliacion.application.banco.ExtractorDesconocidoException;
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
}
