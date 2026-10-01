package com.conciliacion.application.empresa;

import java.util.List;

/**
 * De donde sale la lista de empresas.
 *
 * Existe como interfaz, y no para "desacoplar": la pantalla necesita la lista y
 * la fuente de verdad es Xubio. Lo que compra el interfaz es que el listado se
 * pueda probar con una lista fija, sin un token de verdad mocked ni un HTTP
 * Server en cada test.
 *
 * Ojo con el contrato de `consultar()`: devuelve la lista INCLUYENDO las entradas
 * que no pudieron verificarse, con `estado = SIN_ACCESO`. No se filtran. Devolver
 * solo las que respondieron hace que una credencial vencida parezca una empresa
 * borrada, y el usuario va a mirar la configuracion de Xubio en vez de la app.
 */
public interface EmpresasXubio {

    /**
     * @return las empresas configuradas. Vacio si la fuente esta apagada, que es un
     *         estado normal y no un error: la pantalla muestra "ninguna configurada".
     */
    List<Empresa> consultar();
}
