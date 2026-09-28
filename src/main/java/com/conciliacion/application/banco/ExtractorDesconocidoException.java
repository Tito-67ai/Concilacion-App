package com.conciliacion.application.banco;

/**
 * Se pide un extractor que no esta registrado. Es un 404: el cliente esta pidiendo
 * algo que no existe, no esta mandando algo malformado.
 *
 * Lleva la lista de los que SI existen porque el error mas comun va a ser tipear mal
 * el codigo (GALICIA en vez de GALICIA_API), y con la lista a mano el cliente se
 * corrige solo sin tener que adivinar.
 */
public class ExtractorDesconocidoException extends RuntimeException {

    private final String codigoPedido;
    private final java.util.List<String> codigosDisponibles;

    public ExtractorDesconocidoException(String codigoPedido, java.util.List<String> codigosDisponibles) {
        super("No hay un extractor con codigo '" + codigoPedido + "'. Disponibles: "
                + (codigosDisponibles.isEmpty() ? "(ninguno)" : String.join(", ", codigosDisponibles)));
        this.codigoPedido = codigoPedido;
        this.codigosDisponibles = codigosDisponibles;
    }

    public String getCodigoPedido() { return codigoPedido; }
    public java.util.List<String> getCodigosDisponibles() { return codigosDisponibles; }
}
