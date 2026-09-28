/**
 * Contrato con la API. Debe coincidir con lo que Jackson serializa.
 *
 * Los enums van como uniones de literales y no como `string` a proposito: un
 * comentario no se compila, pero 'CONCILADO' (con una L de menos) si se
 * compila y despues el filtro del backend no matchea nunca.
 */

export type EstadoConciliacion = 'PENDIENTE' | 'CONCILIADO' | 'DESCARTADO';
export type OrigenMovimiento = 'XUBIO' | 'MANUAL' | 'OTRO';

/** Jackson manda LocalDate como "yyyy-MM-dd" (sin hora ni zona). */
export type IsoDate = string;

/** Jackson manda LocalDateTime como ISO-8601 con offset. */
export type IsoDateTime = string;

export interface CuentaBancaria {
  id: number;
  nombre: string;
  banco: string;
  cbu: string;
}

export interface CuentaContable {
  id: number;
  codigo: string;
  nombre: string;
}

export interface CircuitoContable {
  id: number;
  nombre: string;
}

export interface OpcionesFiltro {
  cuentasBancarias: CuentaBancaria[];
  cuentasContables: CuentaContable[];
  circuitos: CircuitoContable[];
}

export interface MovimientoBancario {
  id: number;
  /** Derivado: viene de la cuenta, no es una columna propia. */
  cbu: string;
  fecha: IsoDate;
  detalle: string;
  /**
   * Llega como numero JSON, asi que aca es un `number` de JS (double IEEE 754).
   * Para MOSTRAR esta bien. Si alguna vez hay que sumar, conviene traerlo como
   * string: los centavos no son representables en binario.
   */
  importe: number;
  esCredito: boolean;
  estado: EstadoConciliacion;
  importadoEn: IsoDateTime;
  cuentaBancaria: CuentaBancaria;
}

export interface MovimientoContable {
  id: number;
  comprobante: string;
  fecha: IsoDate;
  concepto: string;
  importe: number;
  esCredito: boolean;
  origen: OrigenMovimiento;
  estado: EstadoConciliacion;
  cuentaContable: CuentaContable;
  circuito: CircuitoContable;
}

export interface Conciliacion {
  id: number;
  estado: EstadoConciliacion;
  origen: OrigenMovimiento;
  /** Fecha BANCARIA (la del extracto), no la contable. El filtro de fechas usa esta. */
  fecha: IsoDate;
  detalle: string;
  importe: number;
  esCredito: boolean;
  importadoEn: IsoDateTime;
  movimientoBancario: MovimientoBancario;
  movimientoContable: MovimientoContable;
  cuentaBancaria: CuentaBancaria;
  cuentaContable: CuentaContable;
  circuito: CircuitoContable;
}

/** Los cuatro filtros de la barra superior. null = no filtrar por este campo. */
export interface FiltroConciliacion {
  cuentaBancariaId: number | null;
  cuentaContableId: number | null;
  circuitoId: number | null;
  desde: IsoDate | null;
  hasta: IsoDate | null;
}

export const FILTRO_VACIO: FiltroConciliacion = {
  cuentaBancariaId: null,
  cuentaContableId: null,
  circuitoId: null,
  desde: null,
  hasta: null,
};
