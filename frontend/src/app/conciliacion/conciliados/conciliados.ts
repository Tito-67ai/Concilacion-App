import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { ConciliacionService } from '../conciliacion.service';
import { FechaDmPipe } from '../fecha-dm.pipe';
import { FiltroConciliacionUi } from '../filtro/filtro-conciliacion';
import {
  Conciliacion,
  FILTRO_VACIO,
  FiltroConciliacion,
  OpcionesFiltro,
} from '../conciliacion.model';

type TipoMensaje = 'ok' | 'error' | 'info';

interface Mensaje {
  texto: string;
  tipo: TipoMensaje;
}

/**
 * Historico de conciliaciones. A diferencia de la pantalla de pendientes, aca si
 * aplican los CUATRO filtros: una conciliacion ya tiene los dos lados, asi que
 * se puede filtrar por cuenta bancaria, cuenta contable, circuito y rango de
 * fechas.
 *
 * La fecha que se muestra y por la que se filtra es la BANCARIA (la del extracto).
 * La contable puede diferir en dias, asi que mostrar las dos al lado es la unica
 * forma de que el usuario vea cuando un match cruza de mes.
 */
@Component({
  selector: 'app-conciliados',
  imports: [DecimalPipe, FechaDmPipe, FiltroConciliacionUi],
  templateUrl: './conciliados.html',
  styleUrl: './conciliados.css',
})
export class Conciliados implements OnInit {
  private readonly service = inject(ConciliacionService);

  protected readonly opciones = signal<OpcionesFiltro>({
    cuentasBancarias: [],
    cuentasContables: [],
    circuitos: [],
  });
  protected readonly conciliados = signal<Conciliacion[]>([]);
  protected readonly filtro = signal<FiltroConciliacion>({ ...FILTRO_VACIO });
  protected readonly cargando = signal(false);
  protected readonly mensaje = signal<Mensaje | null>(null);
  protected readonly detalleAbierto = signal<number | null>(null);

  ngOnInit(): void {
    this.service.getOpciones().subscribe({
      next: (o) => this.opciones.set(o),
      error: () => this.mensaje.set({ texto: 'No se pudieron cargar las opciones del filtro.', tipo: 'error' }),
    });
    this.cargar();
  }

  protected cargar(): void {
    this.cargando.set(true);
    this.service.getConciliaciones(this.filtro()).subscribe({
      next: (data) => {
        this.conciliados.set(data);
        this.cargando.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.conciliados.set([]);
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

  protected toggleDetalle(id: number): void {
    this.detalleAbierto.update((actual) => (actual === id ? null : id));
  }

  /** Dias de diferencia entre la fecha bancaria y la contable. 0 = mismo dia. */
  protected desfase(c: Conciliacion): number {
    return c.movimientoContable.fecha
      ? Math.round(
          (Date.parse(c.movimientoContable.fecha) - Date.parse(c.fecha)) / 86_400_000,
        )
      : 0;
  }

  private explicar(err: HttpErrorResponse): string {
    if (err.status === 0) {
      return 'No se pudo alcanzar el backend. Probalo con el Spring Boot corriendo en el puerto 8081.';
    }
    return `Error ${err.status} del backend: ${err.message}`;
  }
}
