import { Component, computed, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { FiltroConciliacion, FILTRO_VACIO, OpcionesFiltro } from '../conciliacion.model';

/**
 * Barra de filtro superior, compartida por la pantalla de pendientes y la de
 * conciliados.
 *
 * CONVENCION PARA AGREGAR BOOTSTRAP DESPUES:
 * el template usa el vocabulario de clases de Bootstrap (`.form-select`,
 * `.form-control`, `.form-label`, `.btn`, `.row`, `.col-*`) sobre controles
 * NATIVOS (`<select>`, `<input type="date">`). No hay estilos propios para eso.
 * Cuando quieras, `npm i bootstrap` y ya esta: no hay que tocar el HTML.
 * Para los widgets de Bootstrap (datepicker, modal) despues se agrega
 * `@ng-bootstrap/ng-bootstrap`, que se engancha a estos mismos controles nativos.
 *
 * `mostrarContables` controla si aparecen cuenta contable y circuito. La pantalla
 * de pendientes los oculta porque un pendiente no tiene todavia contrapartida
 * contable: no hay nada que filtrar ahi.
 */
@Component({
  selector: 'app-filtro-conciliacion',
  imports: [FormsModule],
  templateUrl: './filtro-conciliacion.html',
  styleUrl: './filtro-conciliacion.css',
})
export class FiltroConciliacionUi {
  readonly opciones = input.required<OpcionesFiltro>();
  readonly mostrarContables = input(false);
  readonly cargando = input(false);

  /** Emite solo al apretar "Aplicar", no en cada keystroke. */
  readonly aplicar = output<FiltroConciliacion>();
  readonly limpiar = output<void>();

  protected readonly draft = signal<FiltroConciliacion>({ ...FILTRO_VACIO });
  protected readonly hayFiltro = computed(() => {
    const d = this.draft();
    return (
      d.cuentaBancariaId !== null ||
      d.cuentaContableId !== null ||
      d.circuitoId !== null ||
      !!d.desde ||
      !!d.hasta
    );
  });

  /** Los <select> entregan strings; "" significa "no filtrar". */
  protected set(select: 'cuentaBancariaId' | 'cuentaContableId' | 'circuitoId', valor: string): void {
    this.draft.update((f) => ({ ...f, [select]: valor === '' ? null : Number(valor) }));
  }

  protected setFecha(campo: 'desde' | 'hasta', valor: string): void {
    this.draft.update((f) => ({ ...f, [campo]: valor === '' ? null : valor }));
  }

  protected aplicarFiltro(): void {
    this.aplicar.emit({ ...this.draft() });
  }

  protected limpiarFiltro(): void {
    this.draft.set({ ...FILTRO_VACIO });
    this.limpiar.emit();
  }
}
