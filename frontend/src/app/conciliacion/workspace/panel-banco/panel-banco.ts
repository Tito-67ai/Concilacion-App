import { DecimalPipe } from '@angular/common';
import { Component, computed, input, output, signal } from '@angular/core';
import { FechaDmPipe } from '../../fecha-dm.pipe';
import { MovimientoBancario } from '../../conciliacion.model';

/**
 * Panel IZQUIERDO de la pantalla de matching: los movimientos que descarga el banco y
 * todavia no fueron conciliados.
 *
 * Se hace clicking sobre la fila, no con un checkbox. En una pantalla de matching
 * elegi una fila de aca y una de alla: un checkbox mas una fila seleccionada son dos
 * estados para la misma cosa, y siempre se desincronizan. El resaltado de la fila ES
 * la seleccion.
 */
@Component({
  selector: 'app-panel-banco',
  imports: [DecimalPipe, FechaDmPipe],
  templateUrl: './panel-banco.html',
  styleUrl: './panel-banco.css',
})
export class PanelBanco {
  readonly movimientos = input.required<MovimientoBancario[]>();
  readonly seleccionId = input<number | null>(null);
  /** false = todavia no se aplico ningun filtro. Cambia el mensaje de vacio. */
  readonly hayFiltro = input(false);
  readonly procesando = input(false);

  /** Emite el id, o null si se hizo click en la fila que ya estaba seleccionada. */
  readonly seleccionar = output<number | null>();
  readonly buscar = output<string>();
  readonly descartar = output<number>();

  protected readonly texto = signal('');

  protected readonly visibles = computed(() => {
    const q = this.texto().trim().toLowerCase();
    if (!q) {
      return this.movimientos();
    }
    return this.movimientos().filter(
      (m) =>
        m.detalle.toLowerCase().includes(q) ||
        (m.comprobante ?? '').toLowerCase().includes(q) ||
        m.cuentaBancaria.banco.toLowerCase().includes(q),
    );
  });

  protected onBuscar(event: Event): void {
    this.texto.set((event.target as HTMLInputElement).value);
    this.buscar.emit(this.texto());
  }

  protected alternar(m: MovimientoBancario): void {
    this.seleccionar.emit(this.seleccionId() === m.id ? null : m.id);
  }
}
