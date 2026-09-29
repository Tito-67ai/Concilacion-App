package com.conciliacion.application.catalogo;

import java.util.List;

/**
 * Los tres catalogos que alimentan los desplegables del filtro, en una sola
 * respuesta de Xubio.
 *
 * Van juntos y no en tres llamadas sueltas porque la pantalla los pide en una
 * llamada (GET /filtros/opciones). Si se pidieran por separado, con el token ya
 * resuelto, serian tres GET que podrian ir en paralelo; como la version de este
 * snippet hace una llamada por catalogo, agruparlos deja el lugar donde poner el
 * fan-out sin cambiar el contrato de salida.
 */
public record Catalogos(
        List<ItemCatalogo> cuentasBancarias,
        List<ItemCatalogo> cuentasContables,
        List<ItemCatalogo> circuitos) {

    public Catalogos {
        // Una lista null en la respuesta no es "sin cuentas", es un dato roto. Se
        // convierte en vacia para que el sincronizador no reviente con un NPE
        // mil metros adentro, y para que el log quede mostrando que falto el
        // campo en vez de mostrar un vacio que parece real.
        cuentasBancarias = cuentasBancarias == null ? List.of() : List.copyOf(cuentasBancarias);
        cuentasContables = cuentasContables == null ? List.of() : List.copyOf(cuentasContables);
        circuitos = circuitos == null ? List.of() : List.copyOf(circuitos);
    }

    /** Sin nada en los tres catalogos no se puede elegir un filtro. */
    public boolean vacio() {
        return cuentasBancarias.isEmpty() && cuentasContables.isEmpty() && circuitos.isEmpty();
    }

    public int total() {
        return cuentasBancarias.size() + cuentasContables.size() + circuitos.size();
    }
}
