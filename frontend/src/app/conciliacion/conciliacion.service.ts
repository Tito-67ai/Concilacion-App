import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Conciliacion,
  FiltroConciliacion,
  ImportacionBancaria,
  InfoExtractor,
  IsoDate,
  MovimientoBancario,
  OpcionesFiltro,
} from './conciliacion.model';

/**
 * URL relativa a proposito. El dev proxy (proxy.conf.json) la reenvia al backend,
 * asi que la peticion es same-origin, el navegador ni evalua CORS, y no hay
 * puertos hardcodeados que se rompan al cambiar de maquina.
 */
const BASE = '/api/conciliaciones';
const IMPORT_BASE = '/api/importaciones';

@Injectable({ providedIn: 'root' })
export class ConciliacionService {
  private readonly http = inject(HttpClient);

  /** Opciones de los desplegables, en una sola llamada. */
  getOpciones(): Observable<OpcionesFiltro> {
    return this.http.get<OpcionesFiltro>(`${BASE}/filtros/opciones`);
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
   * 200 = conciliado. 409 = existe pero no hay coincidencia exacta. 404 = no existe.
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

  /** Que fuentes hay: DEMO (la semilla) y CSV hoy; APIs bancarias en el futuro. */
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

  /** Sube el archivo que bajo el usuario del home banking. */
  importarArchivo(
    cuentaBancariaId: number,
    archivo: File,
    extractor = 'CSV',
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

  /** Los null no van: omitirlos es lo que hace que "sin filtro" signifique todo. */
  private construirParams(filtro: FiltroConciliacion, incluirContables: boolean): HttpParams {
    let params = new HttpParams();
    if (filtro.cuentaBancariaId !== null) {
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
