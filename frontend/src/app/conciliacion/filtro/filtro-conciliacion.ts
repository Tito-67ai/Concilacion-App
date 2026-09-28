import { Component, computed, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { FILTRO_VACIO, FiltroConciliacion, OpcionesFiltro } from '../conciliacion.model';

/**
 * Barra de filtro de la pantalla de conciliacion.
 *
 * CONVENCION PARA AGREGAR BOOTSTRAP DESPUES:
 * el template usa el vocabulario de clases de Bootstrap (`.form-select`,
 * `.form-control`, `.form-label`, `.btn`, `.row`, `.col-md-4`, `.card`, `.input-group`)
 * sobre controles NATIVOS (`<select>`, `<input type="date">`). No hay estilos propios
 * para eso. Cuando quieras, `npm i bootstrap` y ya esta: no hay que tocar el HTML.
 * Para los widgets (datepicker, modal) despues se agrega
 * `@ng-bootstrap/ng-bootstrap`, que se engancha a estos mismos controles nativos.
 *
 * `mostrarContables` controla si aparecen cuenta contable y circuito. La pantalla
 * vieja de pendientes los oculta porque un pendiente todavia no tiene contrapartida
 * contable: no hay nada que filtrar ahi.
 *
 * `exigirCampos` es lo que hace que esta pantalla NO se cargue sola. Con el en true:
 * los campos marcados con * son obligatorios, "Aplicar" arranca deshabilitado, y
 * hasta que la persona elige no se emite nada. Es la diferencia entre una pantalla
 * que te muestra 400 movimientos de todo el banco y una que te espera.
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
  /** Activa la obligatoriedad de los campos con *. La pantalla vieja no la usa. */
  readonly exigirCampos = input(false);
  readonly cargando = input(false);

  /** Emite solo al apretar "Aplicar", no en cada keystroke. */
  readonly aplicar = output<FiltroConciliacion>();
  readonly limpiar = output<void>();

  protected readonly draft = signal<FiltroConciliacion>({ ...FILTRO_VACIO });
  protected readonly abierto = signal(true);

  /**
   * Que falta para poder aplicar. Se muestra al lado del boton, no como error rojo:
   * un formulario que todavia no se completo no esta "mal", esta a medio hacer.
   */
  protected readonly faltan = computed<string[]>(() => {
    if (!this.exigirCampos()) {
      return [];
    }
    const d = this.draft();
    const f: string[] = [];
    if (this.mostrarContables()) {
      if (d.cuentaContableId === null) {
        f.push('la cuenta contable');
      }
      if (d.circuitoId === null) {
        f.push('el circuito');
      }
    }
    if (!d.desde) {
      f.push('la fecha desde');
    }
    if (!d.hasta) {
      f.push('la fecha hasta');
    }
    return f;
  });

  /** Un rango al reves no es "sin resultados", es un filtro mal armado. */
  protected readonly rangoInvalido = computed(() => {
    const d = this.draft();
    return !!d.desde && !!d.hasta && d.desde > d.hasta;
  });

  protected readonly puedeAplicar = computed(() => this.faltan().length === 0 && !this.rangoInvalido());

  protected set<K extends keyof FiltroConciliacion>(campo: K, valor: FiltroConciliacion[K]): void {
    this.draft.update((d) => ({ ...d, [campo]: valor }));
  }

  /** El "x" de cada select. Vuelven todos los campos a null, no solo ese. */
  protected limpiarCampo(campo: keyof FiltroConciliacion): void {
    this.draft.update((d) => ({ ...d, [campo]: null }));
  }

  protected aplicarFiltro(): void {
    if (!this.puedeAplicar()) {
      return;
    }
    this.aplicar.emit({ ...this.draft() });
  }

  protected limpiarTodo(): void {
    this.draft.set({ ...FILTRO_VACIO });
    this.limpiar.emit();
  }
}
