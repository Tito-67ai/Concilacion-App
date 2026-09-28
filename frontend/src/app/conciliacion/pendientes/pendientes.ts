import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { ConciliacionService } from '../conciliacion.service';
import { FechaDmPipe } from '../fecha-dm.pipe';
import { FiltroConciliacionUi } from '../filtro/filtro-conciliacion';
import {
  FILTRO_VACIO,
  FiltroConciliacion,
  MovimientoBancario,
  OpcionesFiltro,
} from '../conciliacion.model';

type TipoMensaje = 'ok' | 'error' | 'info';

interface Mensaje {
  texto: string;
  tipo: TipoMensaje;
}

/**
 * OJO CON SEÑALES: el proyecto es zoneless (no hay zone.js), asi que la change
 * detection no se dispara sola cuando muta un campo comun dentro de un
 * subscribe de RxJS. Por eso el estado va en signals. Con campos comunes
 * (`pendientes: Movimiento[]` + `this.pendientes = data`) el componente
 * COMPILARIA bien y la tabla se quedaria vacia para siempre, sin dar error.
 */
@Component({
  selector: 'app-pendientes',
  imports: [DecimalPipe, FechaDmPipe, FiltroConciliacionUi],
  templateUrl: './pendientes.html',
  styleUrl: './pendientes.css',
})
export class Pendientes implements OnInit {
  private readonly service = inject(ConciliacionService);

  protected readonly opciones = signal<OpcionesFiltro>({
    cuentasBancarias: [],
    cuentasContables: [],
    circuitos: [],
  });
  protected readonly pendientes = signal<MovimientoBancario[]>([]);
  protected readonly filtro = signal<FiltroConciliacion>({ ...FILTRO_VACIO });
  protected readonly cargando = signal(false);
  protected readonly mensaje = signal<Mensaje | null>(null);
  protected readonly procesandoId = signal<number | null>(null);

  ngOnInit(): void {
    this.service.getOpciones().subscribe({
      next: (o) => this.opciones.set(o),
      error: () => this.mensaje.set({ texto: 'No se pudieron cargar las opciones del filtro.', tipo: 'error' }),
    });
    this.cargar();
  }

  protected cargar(): void {
    this.cargando.set(true);
    this.service.getPendientes(this.filtro()).subscribe({
      next: (data) => {
        this.pendientes.set(data);
        this.cargando.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.pendientes.set([]);
        this.cargando.set(false);
        this.mensaje.set({ texto: this.explicar(err), tipo: 'error' });
      },
    });
  }

  protected aplicarFiltro(f: FiltroConciliacion): void {
    this.filtro.set(f);
    this.cargar();
  }

  protected limpiarFiltro(): void {
    this.filtro.set({ ...FILTRO_VACIO });
    this.cargar();
  }

  /**
   * 200 = conciliado, 409 = existe pero sin coincidencia, 404 = no existe.
   * Antes el backend devolvia 200 con null para los tres casos y el mensaje era
   * inventado; ahora cada status dice la verdad.
   */
  protected autoconciliar(id: number): void {
    this.procesandoId.set(id);
    this.service.autoconciliar(id).subscribe({
      next: (c) => {
        this.procesandoId.set(null);
        this.mensaje.set({
          texto: `Movimiento #${id} conciliado contra ${c.movimientoContable.comprobante}.`,
          tipo: 'ok',
        });
        this.cargar();
      },
      error: (err: HttpErrorResponse) => {
        this.procesandoId.set(null);
        this.mensaje.set({
          texto: this.explicarAutoconciliar(err, id),
          tipo: err.status === 404 ? 'error' : 'info',
        });
      },
    });
  }

  /** Saca de la cola lo que no va a tener contrapartida: comisiones, traspasos. */
  protected descartar(id: number): void {
    this.procesandoId.set(id);
    this.service.descartar(id).subscribe({
      next: () => {
        this.procesandoId.set(null);
        this.mensaje.set({
          texto: `Movimiento #${id} descartado: no se concilia contra nada contable.`,
          tipo: 'info',
        });
        this.cargar();
      },
      error: (err: HttpErrorResponse) => {
        this.procesandoId.set(null);
        this.mensaje.set({ texto: `No se pudo descartar el movimiento #${id}.`, tipo: 'error' });
      },
    });
  }

  private explicarAutoconciliar(err: HttpErrorResponse, id: number): string {
    if (err.status === 409) {
      return `El movimiento #${id} no tiene ningun par contable con fecha e importe exactos: sigue pendiente.`;
    }
    if (err.status === 404) {
      return `El movimiento #${id} no existe.`;
    }
    return this.explicar(err);
  }

  private explicar(err: HttpErrorResponse): string {
    if (err.status === 0) {
      return 'No se pudo alcanzar el backend. Probalo con el Spring Boot corriendo en el puerto 8081.';
    }
    return `Error ${err.status} del backend: ${err.message}`;
  }
}
