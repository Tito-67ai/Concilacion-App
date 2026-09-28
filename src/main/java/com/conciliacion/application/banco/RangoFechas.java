package com.conciliacion.application.banco;

import java.time.LocalDate;

/**
 * Ventana de fechas que se le pide a una fuente.
 *
 * OJO con esto, porque es la trampa clasica al conectar una API bancaria: se pide
 * [hoy-5, hoy] porque parece que alcanza, y manana aparece un pago con valor de hoy
 * que se confirmaba ayer y que nadie trajo. Por eso toda sincronizacion deberia
 * pedir un margen hacia atras y dejar que el UNIQUE de
 * (cuenta, comprobante) descarte lo que ya estaba.
 *
 * cualquiera de los dos puede venir null = "sin limite de ese lado".
 */
public record RangoFechas(LocalDate desde, LocalDate hasta) {

    public static RangoFechas de(LocalDate desde, LocalDate hasta) {
        return new RangoFechas(desde, hasta);
    }
}
