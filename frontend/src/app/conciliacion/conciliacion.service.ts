import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Conciliacion,
  Empresa,
  FiltroConciliacion,
  ImportacionBancaria,
  InfoExtractor,
  InfoFormatoExportacion,
  IsoDate,
  LadoReporte,
  MovimientoBancario,
  MovimientoContable,
  OpcionesFiltro,
} from './conciliacion.model';

/**
 * URL relativa a proposito. El dev proxy (proxy.conf.json) la reenvia al backend,
 * asi que la peticion es same-origin, el navegador ni evalua CORS, y no hay
 * puertos hardcodeados que se rompan al cambiar de maquina.
 */
const BASE = '/api/conciliaciones';
const IMPORT_BASE = '/api/importaciones';
const EXPORT_BASE = '/api/exportaciones';

@Injectable({ providedIn: 'root' })
export class ConciliacionService {
  private readonly http = inject(HttpClient);

  /** Opciones de los desplegables, en una sola llamada. */
  getOpciones(): Observable<OpcionesFiltro> {
    return this.http.get<OpcionesFiltro>(`${BASE}/filtros/opciones`);
  }

  /**
   * Empresas que se pueden operar: una por App Cliente de Xubio.
   *
   * El nombre de cada una lo trae `GET /miempresa` de Xubio, no la configuracion.
   * Por eso el endpoint es una llamada remota por empresa y por eso devuelve 200
   * aunque alguna falle: cada una viene con `estado = SIN_ACCESO` y el motivo.
   *
   * Un 503 aca seria peor: la barra quedaria en error y no se podrian elegir las
   * empresas que si funcionan. Con la fuente apagada la lista viene vacia, que es
   * un estado normal y no un error.
   */
  getEmpresas(): Observable<Empresa[]> {
    return this.http.get<Empresa[]>(`${BASE}/empresas`);
  }

  /**
   * Acepta cuenta bancaria y fechas. NO acepta cuenta contable ni circuito: un
   * pendiente todavia no tiene contrapartida contable, asi que no hay que que
   * filtrar por eso. El backend los ignora.
   */
  getPendientes(filtro: FiltroConciliacion): Observable<MovimientoBancario[]> {
    return this.http.get<MovimientoBancario[]>(`${BASE}/pendientes`, {
      params: this.construirParams(filtro, false),
    });
  }

  /** Historico de conciliaciones, con los 4 filtros. */
  getConciliaciones(filtro: FiltroConciliacion): Observable<Conciliacion[]> {
    return this.http.get<Conciliacion[]>(BASE, {
      params: this.construirParams(filtro, true),
    });
  }

  /**
   * Panel DERECHO de la pantalla de matching: los contables sin pareja.
   *
   * Acepta cuenta contable, circuito y fechas, NO cuenta bancaria. En este lado todavia
   * no se sabe de que cuenta bancaria viene el movimiento: eso se conoce recien cuando
   * se elige la fila del otro panel. Por eso la barra de filtros de esa pantalla tiene
   * los dos grupos de campos juntos.
   */
  getPendientesContables(filtro: FiltroConciliacion): Observable<MovimientoContable[]> {
    return this.http.get<MovimientoContable[]>(`${BASE}/pendientes-contables`, {
      params: this.construirParams(filtro, true, false),
    });
  }

  /**
   * Conciliacion manual: POST con el PAR, no con un id suelto.
   *
   * A diferencia de autoconciliar, NO busca por fecha e importe: para eso esta la
   * pantalla, para los casos donde los dos lados dicen cosas distintas.
   *
   * 200 =oki. 400 = vinieron mal los ids. 404 = alguno no existe.
   * 409 = alguno ya no esta pendiente, o son de signos distintos (credito contra
   * debito). Ese ultimo llega con {error: 'SIGNO_INCOMPATIBLE', detalle: '...'} para
   * que el mensaje diga eso y no el genérico "no coincide".
   */
  conciliar(idBanco: number, idContable: number): Observable<Conciliacion> {
    return this.http.post<Conciliacion>(BASE, { idBanco, idContable });
  }

  /**
   * 200 = conciliado. 409 = existe pero sin coincidencia. 404 = no existe.
   * Los tres casos se distinguen por status, asi que el componente puede decir la
   * verdad en vez de inventar un mensaje.
   */
  autoconciliar(id: number): Observable<Conciliacion> {
    return this.http.post<Conciliacion>(`${BASE}/${id}/autoconciliar`, {});
  }

  /** Saca de la cola lo que no tendra contrapartida (comisiones, traspasos). */
  descartar(id: number): Observable<void> {
    return this.http.post<void>(`${BASE}/${id}/descartar`, {});
  }

  // ─── Importacion bancaria ─────────────────────────────────────────────────────
  // El backend ya expone esto; la UI todavia no lo llama. Lo que sigue es el
  // contrato declarado, para que cuando se agregue el boton Importar no haya que
  // adivinar la forma de las respuestas.

  /**
   * Que fuentes hay. Hoy: EXCEL y PDF, las dos de archivo. Las APIs bancarias
   * aparecen solas cuando alguien escriba su extractor.
   */
  getExtractores(): Observable<InfoExtractor[]> {
    return this.http.get<InfoExtractor[]>(`${IMPORT_BASE}/extractores`);
  }

  /**
   * Corre una fuente que se conecta sola. Repetirla es seguro: el backend deduplica
   * por (cuenta, comprobante) y devuelve `insertados: 0` con todo en `duplicados`.
   */
  importar(codigo: string, cuentaBancariaId: number, desde?: IsoDate, hasta?: IsoDate): Observable<ImportacionBancaria> {
    return this.http.post<ImportacionBancaria>(
      `${IMPORT_BASE}/extractores/${encodeURIComponent(codigo)}`,
      {},
      { params: this.construirParamsImportacion(cuentaBancariaId, desde, hasta) },
    );
  }

  /**
   * Sube el archivo que bajo el usuario del home banking.
   *
   * `extractor` NO tiene valor por defecto, y antes si lo tenia ('CSV'). Es a
   * proposito: un default aca es un default invisible. El codigo llama a este metodo
   * desde el menu, y ese menu siempre manda el `codigo` del extractor que eligio el
   * usuario. Si faltara, el backend recibiria 'CSV', que ya no existe, y devolveria
   * 404 con un mensaje que no namesake con ninguna de las dos opciones del menu.
   * Que falte un argumento obligatorio es un error de compilacion; que falte en
   * ejecucion es un 404 a las dos de la manana.
   */
  importarArchivo(
    cuentaBancariaId: number,
    archivo: File,
    extractor: string,
    desde?: IsoDate,
    hasta?: IsoDate,
  ): Observable<ImportacionBancaria> {
    const form = new FormData();
    form.append('archivo', archivo, archivo.name);
    const params = this.construirParamsImportacion(cuentaBancariaId, desde, hasta).set('extractor', extractor);
    return this.http.post<ImportacionBancaria>(`${IMPORT_BASE}/archivo`, form, { params });
  }

  /** Historial de corridas. Sin `cuentaBancariaId` devuelve todas. */
  getHistorialImportaciones(cuentaBancariaId?: number | null): Observable<ImportacionBancaria[]> {
    let params = new HttpParams();
    if (cuentaBancariaId != null) {
      params = params.set('cuentaBancariaId', cuentaBancariaId);
    }
    return this.http.get<ImportacionBancaria[]>(IMPORT_BASE, { params });
  }

  // ─── Exportacion ──────────────────────────────────────────────────────────────

  /**
   * Que formatos se pueden descargar. Es lo que lista el menu Exportar.
   *
   * El menu se arma con la respuesta y no con una lista escrita en el HTML a
   * proposito: agregar un renderizador en Java (uno nuevo, digamos) lo hace
   * aparecer solo, sin tocar la pantalla. Con la lista en el HTML, el dia del
   * formato nuevo el menu queda mintiendo: ofrece lo de ayer.
   *
   * El CSV NO viene en esta lista porque se arma en el navegador, no en el
   * servidor. Lo agrega el componente a mano, y el comentario del modelo explica
   * por que esa excepcion esta justificada.
   */
  getFormatosExportacion(): Observable<InfoFormatoExportacion[]> {
    return this.http.get<InfoFormatoExportacion[]>(`${EXPORT_BASE}/formatos`);
  }

  /**
   * Pide el archivo al servidor y lo devuelve como Blob, con la respuesta COMPLETA.
   *
   * `observe: 'response'` y no solo `'blob'` a proposito: el nombre del archivo lo
   * decide el backend en el `Content-Disposition` (le pone el rango de fechas y el
   * lado adentro, para que en la carpeta de descargas se vea cual es cual). Si el
   * nombre lo armara el cliente, habria que reimplementar esa regla en TypeScript y
   * las dos copias empezarían a diferir en el primer ajuste.
   */
  descargarReporte(
    formato: string,
    filtro: FiltroConciliacion,
    busqueda: string,
    lado: LadoReporte,
  ): Observable<HttpResponse<Blob>> {
    return this.http.get(`${EXPORT_BASE}/${encodeURIComponent(formato)}`, {
      params: this.construirParamsExportacion(filtro, busqueda, lado),
      observe: 'response',
      responseType: 'blob',
    });
  }

  private construirParamsExportacion(
    filtro: FiltroConciliacion,
    busqueda: string,
    lado: LadoReporte,
  ): HttpParams {
    let params = new HttpParams().set('lado', lado);
    if (filtro.cuentaBancariaId !== null) {
      params = params.set('cuentaBancariaId', filtro.cuentaBancariaId);
    }
    if (filtro.cuentaContableId !== null) {
      params = params.set('cuentaContableId', filtro.cuentaContableId);
    }
    if (filtro.circuitoId !== null) {
      params = params.set('circuitoId', filtro.circuitoId);
    }
    if (filtro.desde) {
      params = params.set('desde', filtro.desde);
    }
    if (filtro.hasta) {
      params = params.set('hasta', filtro.hasta);
    }
    // Texto vacio NO viaja. Mandarlo como `busqueda=` haria que el backend lo
    // tratara como "no hay filtro" igual que si no viniera, asi que el resultado
    // seria el mismo, pero el pedido queda con un parametro vacio que confunde al
    // que lea el log del servidor.
    if (busqueda.trim()) {
      params = params.set('busqueda', busqueda.trim());
    }
    return params;
  }

  /**
   * Arma los query params. Los null no van: omitirlos es lo que hace que "sin filtro"
   * signifique todo.
   *
   * `incluirContables` / `incluirCuentaBancaria` dicen QUE campos son los que el
   * endpoint mira. Antes era un solo booleano y por eso `pendientes-contables` mandaba
   * `cuentaBancariaId`: el backend lo ignoraba, asi que no se notaba, pero en cuanto
   * el endpoint lo respetara el panel derecho filtraria por una cuenta bancaria que
   * justamente no corresponde. Es peor mandar de mas que no mandar, porque el dia
   * que el backend cambie el comportamiento el filtro aparece solo y nadie lo revisa.
   */
  private construirParams(
    filtro: FiltroConciliacion,
    incluirContables: boolean,
    incluirCuentaBancaria = true,
  ): HttpParams {
    let params = new HttpParams();
    if (incluirCuentaBancaria && filtro.cuentaBancariaId !== null) {
      params = params.set('cuentaBancariaId', filtro.cuentaBancariaId);
    }
    if (incluirContables) {
      if (filtro.cuentaContableId !== null) {
        params = params.set('cuentaContableId', filtro.cuentaContableId);
      }
      if (filtro.circuitoId !== null) {
        params = params.set('circuitoId', filtro.circuitoId);
      }
    }
    if (filtro.desde) {
      params = params.set('desde', filtro.desde);
    }
    if (filtro.hasta) {
      params = params.set('hasta', filtro.hasta);
    }
    return params;
  }

  private construirParamsImportacion(cuentaBancariaId: number, desde?: IsoDate, hasta?: IsoDate): HttpParams {
    let params = new HttpParams().set('cuentaBancariaId', cuentaBancariaId);
    if (desde) {
      params = params.set('desde', desde);
    }
    if (hasta) {
      params = params.set('hasta', hasta);
    }
    return params;
  }
}
