import { Pipe, PipeTransform } from '@angular/core';

/** Regex de "yyyy-MM-dd": tres grupos, todo digitos, nada mas. */
const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * Convierte el "yyyy-MM-dd" que manda Jackson (LocalDate) a "dd/MM/yyyy".
 *
 * POR QUE NO USAR `| date` ACA:
 * Angular parsea "2026-09-20" como medianoche UTC, y en Argentina (UTC-3) eso
 * cae en el dia anterior: la fila del 20/09 se muestra como 19/09. El bug es
 * silencioso y off-by-one, la peor clase de bug en una conciliacion.
 *
 * Armar el texto con regex no toca el objeto Date, asi que no hay zona horaria
 * que pueda meterla. Y el regex evita el falso positivo de contar 3 partes:
 * "no-es-fecha" tambien split en 3, y con un chequeo por cantidad de partes
 * devolvia "fecha/es/no".
 */
@Pipe({ name: 'fechaDm' })
export class FechaDmPipe implements PipeTransform {
  transform(iso: string | null | undefined): string {
    if (!iso) {
      return '';
    }
    const partes = ISO_DATE.exec(iso);
    if (!partes) {
      return iso;
    }
    const [, anio, mes, dia] = partes;
    return `${dia}/${mes}/${anio}`;
  }
}
