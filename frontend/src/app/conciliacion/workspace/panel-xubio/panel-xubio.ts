import { DecimalPipe } from '@angular/common';
import { Component, computed, input, output, signal } from '@angular/core';
import { FechaDmPipe } from '../../fecha-dm.pipe';
import { MovimientoBancario, MovimientoContable } from '../../conciliacion.model';

/**
 * Panel DERECHO de la pantalla de matching: los movimientos contables (los de
 * Xubio / el sistema) que todavia no fueron conciliados contra nada.
 *
 * `exactoPorId` es la parte util de esta pantalla. Con una fila bancaria
 * seleccionada, marca CUALES de estas filas conciliarian solas: misma fecha, mismo
 * signo, mismo importe. No es un filtro, no oculta nada: es una senalizacion para
 * que la persona no tenga que comparar 40 filas a ojo, y para que el caso obvio se
 * vea antes de apretar "Autoconciliar" y discover que no habia coincidencia.
 */
@Component({
  selector: 'app-panel-xubio',
  imports: [DecimalPipe, FechaDmPipe],
  templateUrl: './panel-xubio.html',
  styleUrl: './panel-xubio.css',
})
export class PanelXubio {
  readonly movimientos = input.required<MovimientoContable[]>();
  readonly seleccionId = input<number | null>(null);
  readonly hayFiltro = input(false);
  /** La fila bancaria elegida: define contra que se marca el "exacto". */
  readonly contraparte = input<MovimientoBancario | null>(null);

  readonly seleccionar = output<number | null>();
  readonly buscar = output<string>();

  protected readonly texto = signal('');

  protected readonly visibles = computed(() => {
    const q = this.texto().trim().toLowerCase();
    if (!q) {
      return this.movimientos();
    }
    return this.movimientos().filter(
      (m) =>
        m.concepto.toLowerCase().includes(q) ||
        m.comprobante.toLowerCase().includes(q) ||
        m.cuentaContable.codigo.toLowerCase().includes(q) ||
        m.circuito.nombre.toLowerCase().includes(q),
    );
  });

  /**
   * Que ids califican para autoconciliar contra la contraparte elegida.
   *
   * Mismo criterio que el backend (fecha, signo e importe), replicado aca solo para
   * PINTAR la fila. La decision sigue siendo del servidor: si este mapa se
   * equivoca, el boton avisa con el 409 en vez de conciliar algo que no era.
   *
   * La tolerancia de 0.005 es por los centavos: los importes llegan como double y
   * 152000.0 == 152000.0 pero 1850.75 no siempre se compares igual si uno de los dos
   * viene de un CSV con mas decimales de los que tiene el otro.
   */
  protected readonly exactoPorId = computed<ReadonlySet<number>>(() => {
    const b = this.contraparte();
    if (!b) {
      return new Set<number>();
    }
    return new Set(
      this.movimientos()
        .filter(
          (c) =>
            c.fecha === b.fecha &&
            c.esCredito === b.esCredito &&
            Math.abs(c.importe - b.importe) < 0.005,
        )
        .map((c) => c.id),
    );
  });

  protected onBuscar(event: Event): void {
    this.texto.set((event.target as HTMLInputElement).value);
    this.buscar.emit(this.texto());
  }

  protected alternar(m: MovimientoContable): void {
    this.seleccionar.emit(this.seleccionId() === m.id ? null : m.id);
  }
}
