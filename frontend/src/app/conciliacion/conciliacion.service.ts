import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Conciliacion,
  FiltroConciliacion,
  MovimientoBancario,
  OpcionesFiltro,
} from './conciliacion.model';

/**
 * URL relativa a proposito. El dev proxy (proxy.conf.json) la reenvia al backend,
 * asi que la peticion es same-origin, el navegador ni evalua CORS, y no hay
 * puertos hardcodeados que se rompan al cambiar de maquina.
 */
const BASE = '/api/conciliaciones';

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
}
