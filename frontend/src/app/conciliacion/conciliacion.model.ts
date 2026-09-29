/**
 * Contrato con la API. Debe coincidir con lo que Jackson serializa.
 *
 * Los enums van como uniones de literales y no como `string` a proposito: un
 * comentario no se compila, pero 'CONCILADO' (con una L de menos) si se
 * compila y despues el filtro del backend no matchea nunca.
 */

export type EstadoConciliacion = 'PENDIENTE' | 'CONCILIADO' | 'DESCARTADO';

/**
 * De donde salio la fila. Los dos lados usan el mismo enum.
 *
 * Ojo con 'XUBIO_API': antes el valor era 'XUBIO', que no decia si venia de la API
 * o de una carga manual. Esa distincion es justamente la que hace falta para no pisar
 * con una sincronizacion lo que el usuario escribio a mano.
 *
 * 'CSV' sigue en la lista aunque hoy no haya ningun extractor de CSV: el enum se
 * persiste como STRING en la base, asi que las filas importadas antes lo tienen
 * escrito. Si se sacara de aca, TS dejaria de aceptar esas filas al cargarlas.
 * Un enum no se cleans: se deprecia.
 */
export type OrigenMovimiento =
  | 'MANUAL'
  | 'CSV'
  | 'EXCEL'
  | 'PDF'
  | 'API_BANCARIA'
  | 'XUBIO_API'
  | 'OTRO';

/** Jackson manda LocalDate como "yyyy-MM-dd" (sin hora ni zona). */
export type IsoDate = string;

/** Jackson manda LocalDateTime como ISO-8601 con offset. */
export type IsoDateTime = string;

export interface CuentaBancaria {
  id: number;
  nombre: string;
  banco: string;
  cbu: string;
  /**
   * Cuando se conecto una fuente por ultima vez. Sirve para no volver a bajar desde
   * cero. OJO: NO evita duplicados, para eso esta el `comprobante` del movimiento.
   */
  ultimaImportacionEn?: IsoDateTime | null;
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
  /** Fecha VALOR: la del extracto, y la que usa el filtro de la pantalla. */
  fecha: IsoDate;
  /**
   * Fecha de EJECUCION. Puede diferir de `fecha` (pago acreditado el 28 con valor
   * 30). El backend la manda siempre; queda opcional para no romper filas viejas.
   */
  fechaOperacion?: IsoDate | null;
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
  origen: OrigenMovimiento;
  /**
   * ID de transaccion del BANCO. Es lo que hace idempotente una reimportacion: la
   * deduplicacion es por (cuenta, comprobante). Viene null en las altas manuales y
   * en las fuentes que no lo entregan (un CSV sin esta columna no deduplica).
   */
  comprobante?: string | null;
  /** Saldo de la cuenta despues del movimiento, si la fuente lo da. */
  saldo?: number | null;
  /** Corrida de importacion de la que salio esta fila. */
  importacion?: ImportacionBancaria | null;
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

// ─── Importacion bancaria ───────────────────────────────────────────────────────

/**
 * Corresponde a `ExtractorBancario.aceptaArchivo()` / `puedeEjecutarseSolo()` en el
 * backend. Los dos flags juntos dicen que espera la UI: con `aceptaArchivo` hay que
 * abrir el selector de archivos; con `puedeEjecutarseSolo` alcanza con un boton.
 *
 * El `codigo` es texto libre y NO un enum, a proposito: cada banco nuevo agrega un
 * valor y no obliga a tocar este archivo.
 */
export interface InfoExtractor {
  codigo: string;
  descripcion: string;
  origen: OrigenMovimiento;
  aceptaArchivo: boolean;
  puedeEjecutarseSolo: boolean;
  /**
   * Que archivos acepta, con el punto: ['.xlsx', '.xls']. Viene del backend, no
   * esta escrito aca, para que agregar un extractor no obligue a tocar la pantalla.
   * Vacio = no se filtra el selector.
   */
  extensiones: string[];
}

/**
 * Una corrida de importacion. Es la traza de por que una fila existe: que se pidio,
 * de donde, y cuanto entro de verdad.
 *
 * `leidos - insertados - duplicados` son las filas que se descartaron por formato
 * roto, que es lo unico que no queda en otra tabla.
 */
export interface ImportacionBancaria {
  id: number;
  cuentaBancaria: CuentaBancaria;
  origen: OrigenMovimiento;
  extractor: string;
  desde?: IsoDate | null;
  hasta?: IsoDate | null;
  leidos: number;
  insertados: number;
  duplicados: number;
  iniciadaEn: IsoDateTime;
  terminadaEn?: IsoDateTime | null;
  detalle?: string | null;
}

/**
 * Un formato de salida del reporte, segun `GET /api/exportaciones/formatos`.
 *
 * El menu Exportar se arma con lo que conteste el backend, no con una lista escrita
 * en el HTML. Agregar un renderizador nuevo en Java lo hace aparecer solo, sin tocar
 * esta pantalla.
 *
 * OJO: el CSV NO esta en esta lista. Se arma en el navegador (ver `descargarCsv` en
 * workspace.ts) y por eso se agrega a mano en el menu. Es la unica excepcion, y esta
 * ahi por una razon que no es capricho: el CSV necesita el BOM y el separador ";"
 * para que Excel en es-AR no se coma la coma decimal, y eso sale mas corto en el
 * cliente que con una libreria de CSV en el servidor.
 */
export interface InfoFormatoExportacion {
  formato: string;
  contentType: string;
  /** Sin punto: "xlsx", "pdf". */
  extension: string;
  /** Lo que se muestra en el menu, ya en castellano: "Excel (.xlsx)". */
  etiqueta: string;
}

/**
 * Que pestana se exporta. Va como string y no como el `boolean` "esConciliado" que
 * podria haber puesto en la URL: `PENDIENTES` y `CONCILIADOS` se leen solos en un
 * log del servidor, y `lado=true` no dice de que lado hablabas.
 *
 * Los dos valores tienen que coincidir con `SolicitudReporte.Lado` en Java.
 */
export type LadoReporte = 'PENDIENTES' | 'CONCILIADOS';

/**
 * Cuerpo de error del backend. Antes el backend no devolvia nada estructurado: un
 * fallo llegaba como 500 con el stack trace en el cuerpo, y la UI no tenia forma de
 * distinguir "no existe" de "choca con un cambio concurrente".
 */
export interface ApiError {
  error: string;
  detalle: string;
  /** Solo en EXTRACTOR_DESCONOCIDO: los codigos que si existen. */
  disponibles?: string[];
}
