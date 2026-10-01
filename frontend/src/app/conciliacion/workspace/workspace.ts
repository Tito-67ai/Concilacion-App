import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { finalize, forkJoin } from 'rxjs';
import { ConciliacionService } from '../conciliacion.service';
import { SelectorEmpresa } from '../empresa/selector-empresa';
import { FechaDmPipe } from '../fecha-dm.pipe';
import { FiltroConciliacionUi } from '../filtro/filtro-conciliacion';
import {
  Conciliacion,
  Empresa,
  FiltroConciliacion,
  ImportacionBancaria,
  InfoExtractor,
  InfoFormatoExportacion,
  LadoReporte,
  MovimientoBancario,
  MovimientoContable,
  OpcionesFiltro,
} from '../conciliacion.model';
import { PanelBanco } from './panel-banco/panel-banco';
import { PanelXubio } from './panel-xubio/panel-xubio';

type Tab = 'a-conciliar' | 'conciliados';
type TipoMensaje = 'ok' | 'error' | 'info';

interface Mensaje {
  texto: string;
  tipo: TipoMensaje;
}

/**
 * Pantalla de conciliacion: un filtro arriba, dos pestañas, y en la primera pestana
 * los dos paneles de a uno al lado del otro.
 *
 * LO QUE CAMBIO RESPETO DE LAS DOS PANTALLAS VIEJAS: aqui NO se carga nada al
 * entrar. `filtro()` arranca en null y los dos paneles muestran "elegí los filtros".
 * Antes cada pantalla hacia su fetch con el filtro vacio y listaba todo; con una base
 * de verdad eso son miles de filas de movimientos que no son los que estabas buscando,
 * mezcladas con los de otras cuentas y otros meses. El filtro va primero y los datos
 * despues.
 *
 * `filtro() === null` es lo que distingue los dos mensajes de vacio: "elegí los
 * filtros" (todavia no buscas nada) y "no hay movimientos para estos filtros" (buscaste
 * y no hay). Son pantallas distintas y el mensaje tiene que decir cual es.
 *
 * OJO CON SENALES: el proyecto es zoneless (no hay zone.js), asi que la change detection
 * no se dispara sola cuando muta un campo comun dentro de un subscribe de RxJS. Por eso
 * el estado va en signals. Con campos comunes (`banco: Movimiento[]` + `this.banco = data`)
 * el componente COMPILARIA bien y la tabla se quedaria vacia para siempre, sin dar error.
 */
@Component({
  selector: 'app-workspace',
  imports: [DecimalPipe, FechaDmPipe, FiltroConciliacionUi, PanelBanco, PanelXubio, SelectorEmpresa],
  templateUrl: './workspace.html',
  styleUrl: './workspace.css',
})
export class Workspace implements OnInit {
  private readonly service = inject(ConciliacionService);

  protected readonly opciones = signal<OpcionesFiltro>({
    cuentasBancarias: [],
    cuentasContables: [],
    circuitos: [],
  });

  protected readonly tab = signal<Tab>('a-conciliar');
  protected readonly ayuda = signal(false);

  /** null hasta que se elige una empresa en la barra de arriba. */
  protected readonly empresaActiva = signal<Empresa | null>(null);

  /** null = no se aplico ningun filtro todavia. Ver la nota de arriba. */
  protected readonly filtro = signal<FiltroConciliacion | null>(null);

  protected readonly banco = signal<MovimientoBancario[]>([]);
  protected readonly contable = signal<MovimientoContable[]>([]);
  protected readonly conciliados = signal<Conciliacion[]>([]);

  protected readonly busquedaBanco = signal('');
  protected readonly busquedaContable = signal('');

  protected readonly selBanco = signal<number | null>(null);
  protected readonly selContable = signal<number | null>(null);

  protected readonly cargando = signal(false);
  protected readonly procesando = signal(false);
  protected readonly mensaje = signal<Mensaje | null>(null);

  protected readonly extractores = signal<InfoExtractor[]>([]);
  protected readonly menuImportar = signal(false);
  protected readonly importando = signal(false);

  protected readonly formatos = signal<InfoFormatoExportacion[]>([]);
  protected readonly menuExportar = signal(false);
  protected readonly exportando = signal(false);

  /**
   * Lo que hay en el menu Exportar: lo que el backend sabe hacer, y el CSV al
   * final.
   *
   * El CSV va al final y no primero a proposito. Es el unico de los tres que se
   * arma en el navegador, y si la lista mezclara los dos origenes sin distinguirlos
   * el menu mentiría: `formatos()` devuelve lo que el SERVIDOR sabe exportar, y
   * contestaria "Excel, PDF" mientras el menu muestra tres cosas.
   */
  protected readonly opcionesExportar = computed<InfoFormatoExportacion[]>(() => [
    ...this.formatos(),
    {
      formato: 'csv',
      contentType: 'text/csv',
      extension: 'csv',
      etiqueta: 'CSV',
    },
  ]);

  private ultimoImportado = signal<ImportacionBancaria | null>(null);
  protected readonly ultimaImportacion = this.ultimoImportado.asReadonly();

  ngOnInit(): void {
    // Solo las opciones de los desplegables. NO los movimientos: eso espera al
    // primer "Aplicar".
    this.service.getOpciones().subscribe({
      next: (o) => this.opciones.set(o),
      error: (e) => this.mensaje.set({ tipo: 'error', texto: this.mensajeDe(e) }),
    });

    // Los extractores se piden una vez para saber que ofrece el boton Importar. Si
    // este endpoint no existiera la pantalla igual tendria que funcionar: por eso el
    // error se ignora a proposito y el menu Importar se arma con lo que haya.
    this.service.getExtractores().subscribe({
      next: (x) => this.extractores.set(x),
      error: () => this.extractores.set([]),
    });

    // Los formatos de salida, por la misma razon y con el mismo perdon: si este
    // endpoint no existiera, el menu Exportar tiene que igual ofrecer el CSV, que
    // no depende del servidor. De ahi que el error se ignore y la pantalla no caiga.
    this.service.getFormatosExportacion().subscribe({
      next: (f) => this.formatos.set(f),
      error: () => this.formatos.set([]),
    });
  }

  // ─── Filtro y carga ───────────────────────────────────────────────────────────

  protected aplicar(f: FiltroConciliacion): void {
    this.filtro.set(f);
    this.selBanco.set(null);
    this.selContable.set(null);
    this.busquedaBanco.set('');
    this.busquedaContable.set('');
    this.cargar();
  }

  protected limpiarFiltro(): void {
    this.filtro.set(null);
    this.banco.set([]);
    this.contable.set([]);
    this.conciliados.set([]);
    this.selBanco.set(null);
    this.selContable.set(null);
  }

  /**
   * Las tres consultas van en un forkJoin y no en tres subscribe sueltos: si se
   * cargaran por separado, el contador de "Movimientos a conciliar" podria mostrar un
   * numero con la tabla de al lado todavia en blanco, y la pantalla se veria
   * inconsistente en el medio.
   */
  private cargar(): void {
    const f = this.filtro();
    if (!f) {
      return;
    }
    this.cargando.set(true);
    forkJoin({
      banco: this.service.getPendientes(f),
      contable: this.service.getPendientesContables(f),
      conciliados: this.service.getConciliaciones(f),
    }).subscribe({
      next: (r) => {
        this.banco.set(r.banco);
        this.contable.set(r.contable);
        this.conciliados.set(r.conciliados);
        // Si la fila que estaba elegida desaparecio (otro usuario la concilio, o el
        // filtro cambio), la seleccion tiene que caer: dejarla apuntando a algo
        // invisible habilita el boton Conciliar sobre una fila que ya no esta.
        if (this.selBanco() !== null && !r.banco.some((m) => m.id === this.selBanco())) {
          this.selBanco.set(null);
        }
        if (this.selContable() !== null && !r.contable.some((m) => m.id === this.selContable())) {
          this.selContable.set(null);
        }
      },
      error: (e) => this.mensaje.set({ tipo: 'error', texto: this.mensajeDe(e) }),
      complete: () => this.cargando.set(false),
    });
  }

  // ─── Estado derivado ──────────────────────────────────────────────────────────

  /** El boton Conciliar necesita UNO de cada lado, no dos de un lado. */
  protected readonly puedeConciliar = computed(() => this.selBanco() !== null && this.selContable() !== null);

  /** El autoconciliar busca solo del lado del banco, asi que alcanza con esa fila. */
  protected readonly puedeAutoconciliar = computed(() => this.selBanco() !== null);

  protected readonly contraparte = computed<MovimientoBancario | null>(
    () => this.banco().find((m) => m.id === this.selBanco()) ?? null,
  );

  protected readonly seleccionados = computed<{ banco: MovimientoBancario | null; contable: MovimientoContable | null }>(
    () => ({
      banco: this.banco().find((m) => m.id === this.selBanco()) ?? null,
      contable: this.contable().find((m) => m.id === this.selContable()) ?? null,
    }),
  );

  // ─── Acciones ─────────────────────────────────────────────────────────────────
  //
  // OJO CON EL SPINNER: `procesando` se baja con `finalize`, no con `complete`.
  // En RxJS un `error` hace que el observable termine SIN emitir `complete`, asi que
  // con `complete` el flag queda en true para siempre cuando la peticion falla. Como
  // los botones se habilitan con `!procesando()`, un solo 409 dejaba la pantalla
  // entera bloqueada y sin explicacion de por que: el mensaje de error se veia bien y
  // abajo todo gris, sin boton para reintentar.
  //
  // `finalize` se dispara en los tres casos de terminacion: next, error y unsubscribe.

  protected conciliar(): void {
    const idBanco = this.selBanco();
    const idContable = this.selContable();
    if (idBanco === null || idContable === null) {
      return;
    }
    this.procesando.set(true);
    this.service
      .conciliar(idBanco, idContable)
      .pipe(finalize(() => this.procesando.set(false)))
      .subscribe({
        next: (c) => {
          this.mensaje.set({
            tipo: 'ok',
            texto: `Conciliado: ${c.detalle} — ${c.cuentaBancaria.banco} contra ${c.cuentaContable.codigo} (${c.circuito.nombre}).`,
          });
          this.selBanco.set(null);
          this.selContable.set(null);
          this.cargar();
        },
        error: (e) => this.mensaje.set({ tipo: 'error', texto: this.mensajeDe(e) }),
      });
  }

  protected autoconciliar(id: number): void {
    this.procesando.set(true);
    this.service
      .autoconciliar(id)
      .pipe(finalize(() => this.procesando.set(false)))
      .subscribe({
        next: (c) => {
          this.mensaje.set({ tipo: 'ok', texto: `Autoconciliado: ${c.detalle}.` });
          this.selBanco.set(null);
          this.selContable.set(null);
          this.cargar();
        },
        // Aca el 409 NO significa lo mismo que en conciliar(). El backend devuelve
        // 409 sin cuerpo cuando no encuentra coincidencia exacta, y el mensaje
        // generico de conciliar ("alguno de los dos ya fue conciliado") seria falso:
        // la fila sigue a la vista, en la lista de pendientes. Acusar al usuario de
        // algo que no hizo desorienta mas que decir "no encontre".
        error: (e) => this.mensaje.set({ tipo: 'error', texto: this.mensajeDe(e, SIN_EXACTO) }),
      });
  }

  protected descartar(id: number): void {
    this.procesando.set(true);
    this.service
      .descartar(id)
      .pipe(finalize(() => this.procesando.set(false)))
      .subscribe({
        next: () => {
          this.mensaje.set({ tipo: 'ok', texto: 'Descartado: sale de la cola sin contrapartida contable.' });
          this.selBanco.set(null);
          this.cargar();
        },
        error: (e) => this.mensaje.set({ tipo: 'error', texto: this.mensajeDe(e, YA_CONCILIADO) }),
      });
  }

  // ─── Importar ─────────────────────────────────────────────────────────────────

  /**
   * Correr un extractor necesita una cuenta bancaria: es el destino de los
   * movimientos. Cuenta bancaria es el unico filtro NO obligatorio (en el mockup no
   * lleva asterisco), asi que puede no estar elegida, y sin ella no hay donde meter
   * lo que se descargo. Se avisa en vez de elegir la primera: meter los movimientos
   * en una cuenta que no es la del filtro es peor que no importar.
   */
  private cuentaBancariaParaImportar(): number | null {
    const f = this.filtro();
    if (f?.cuentaBancariaId != null) {
      return f.cuentaBancariaId;
    }
    this.mensaje.set({
      tipo: 'info',
      texto: 'Elegí la cuenta bancaria en los filtros antes de importar: los movimientos se guardan en esa cuenta.',
    });
    return null;
  }

  protected correrExtractor(codigo: string): void {
    const cuenta = this.cuentaBancariaParaImportar();
    if (cuenta === null) {
      return;
    }
    this.importando.set(true);
    this.menuImportar.set(false);
    this.service
      .importar(codigo, cuenta, this.filtro()?.desde ?? undefined, this.filtro()?.hasta ?? undefined)
      .pipe(finalize(() => this.importando.set(false)))
      .subscribe({
        next: (r) => {
          this.ultimoImportado.set(r);
          this.mensaje.set({
            tipo: r.insertados > 0 ? 'ok' : 'info',
            texto:
              r.insertados > 0
                ? `Importados ${r.insertados} movimientos nuevos (${r.duplicados} ya estaban).`
                : `No entró nada nuevo: los ${r.duplicados} leídos ya estaban en la base.`,
          });
          this.cargar();
        },
        error: (e) => this.mensaje.set({ tipo: 'error', texto: this.mensajeDe(e) }),
      });
  }

  /**
   * Sube el archivo al extractor que el usuario eligio del menu.
   *
   * `codigoExtractor` se pasa desde el `@for` del HTML, no se deduce del nombre del
   * archivo ni de la extension. Dos razones:
   *
   *  - Un `.xlsx` mal formado casi siempre es un CSV que alguien cambio de nombre,
   *    y viceversa. Adivinar por la extension manda al backend a un lector que va a
   *    fallar con un mensaje que no es el que corresponde. Eligiendo explicitamente
   *    el error dice la verdad: "este archivo no es un Excel legible".
   *  - El dia que la fuente de PDF acepte tambien imagenes escaneadas, o que
   *    EXCEL acepte `.ods`, el menu ya ofrece las dos cosas sin tocar este metodo.
   */
  protected subirArchivo(event: Event, codigoExtractor: string): void {
    const input = event.target as HTMLInputElement;
    const archivo = input.files?.[0];
    // Se vacia el input antes de nada: si el usuario elige el MISMO archivo dos
    // veces seguidas, sin esto el segundo "change" no dispara y parece que la app
    // se hangueo.
    input.value = '';
    if (!archivo) {
      return;
    }
    const cuenta = this.cuentaBancariaParaImportar();
    if (cuenta === null) {
      return;
    }
    this.importando.set(true);
    this.menuImportar.set(false);
    this.service
      .importarArchivo(cuenta, archivo, codigoExtractor, this.filtro()?.desde ?? undefined, this.filtro()?.hasta ?? undefined)
      .pipe(finalize(() => this.importando.set(false)))
      .subscribe({
        next: (r) => {
          this.ultimoImportado.set(r);
          this.mensaje.set({
            tipo: r.insertados > 0 ? 'ok' : 'info',
            texto: `Leídos ${r.leidos} de ${archivo.name} (${codigoExtractor}): ${r.insertados} nuevos, ${r.duplicados} repetidos.`,
          });
          this.cargar();
        },
        error: (e) => this.mensaje.set({ tipo: 'error', texto: this.mensajeDe(e) }),
      });
  }

  // ─── Exportar ─────────────────────────────────────────────────────────────────

  /**
   * El texto de "Buscar" que va al archivo.
   *
   * La pantalla tiene DOS buscador, uno por panel, y el endpoint de exportacion
   * acepta UNO. Se mandan juntos y el backend aplica el mismo texto a los dos lados
   * del reporte.
   *
   * Y eso no es lo mismo que la pantalla, y conviene saberlo. En pantalla el
   * buscador izquierdo filtra los bancarios y el derecho los contables, por
   * separado. Al exportar, el archivo se arma en el servidor y no puede aplicar
   * dos textos distintos a dos mitades del mismo reporte sin duplicar la consulta.
   *
   * Las dos opciones eran: (a) exportar cada lado por separado con su propio
   * buscador, que obliga a apretar Exportar dos veces, o (b) un solo archivo con un
   * solo criterio, que es lo que hace. Se eligio (b) porque el motivo de usar el
   * buscador es reducir un archivo, y partirlo en dos para tener que abrir dos
   * archivos es peor. Cuando hay texto en los dos, gana el del panel BANCO, que es
   * el de la izquierda y el primero que se lee.
   */
  private busquedaDeExportacion(): string {
    if (this.tab() !== 'a-conciliar') {
      // En la pestana de conciliados no hay buscador: el unico filtro de texto
      // posible es el de los bancarios, asi que no viaja ninguno.
      return '';
    }
    return this.busquedaBanco().trim() || this.busquedaContable().trim();
  }

  /**
   * Exporta lo que se esta VIENDO, no todo lo de la base: si el panel esta filtrado
   * por el texto de "Buscar", el archivo sale con ese filtro. Un "Exportar" que trae
   * 4.000 filas cuando en pantalla hay 12 es la razon por la que la gente deja de
   * usar el boton.
   */
  protected exportar(formato: string): void {
    const f = this.filtro();
    if (!f) {
      this.mensaje.set({ tipo: 'info', texto: 'Aplicá los filtros antes de exportar.' });
      return;
    }
    this.menuExportar.set(false);

    // El CSV se arma aca; Excel y PDF los hace el servidor. Ver `descargarCsv`.
    if (formato === 'csv') {
      this.exportarCsv(f);
      return;
    }

    this.exportando.set(true);
    this.service
      .descargarReporte(formato, f, this.busquedaDeExportacion(), this.ladoDeExportacion())
      .pipe(finalize(() => this.exportando.set(false)))
      .subscribe({
        next: (resp) => this.guardarArchivo(resp, this.nombreDe(formato, f)),
        error: (e) => this.mensajeDeExportacion(e),
      });
  }

  /**
   * Traduce el error de una descarga a un mensaje.
   *
   * NO es `mensajeDe`, y la razon es tecnica pero visible: la descarga se pide con
   * `responseType: 'blob'`, asi que el cuerpo del error tambien llega como Blob y
   * no como el JSON que `mensajeDe` sabe leer. Sin esto, un 422 del backend
   * ("el PDF esta escaneado, hace falta OCR") le llega al usuario como "Error 422."
   * y se pierde justamente la frase que le dice que subio el archivo equivocado.
   *
   * El texto sale del backend y se muestra tal cual. Si el cuerpo no es JSON (un
   * proxy que devuelve HTML, por ejemplo), se cae al mensaje por status, que es
   * feo pero cierto.
   */
  private mensajeDeExportacion(e: unknown): void {
    const resp = e as HttpErrorResponse;
    const cuerpo = resp?.error;

    if (!(cuerpo instanceof Blob)) {
      this.mensaje.set({ tipo: 'error', texto: this.mensajeDe(e) });
      return;
    }

    cuerpo
      .text()
      .then((texto) => {
        let mensaje = `No se pudo generar el archivo (error ${resp.status}).`;
        try {
          const json = JSON.parse(texto) as { detalle?: unknown };
          if (typeof json.detalle === 'string' && json.detalle.length > 0) {
            mensaje = json.detalle;
          }
        } catch {
          // No es JSON. Se deja el mensaje por status.
        }
        this.mensaje.set({ tipo: 'error', texto: mensaje });
      })
      .catch(() => this.mensaje.set({ tipo: 'error', texto: `No se pudo generar el archivo (error ${resp.status}).` }));
  }

  /**
   * Que pestana se exporta.
   *
   * Se manda SIEMPRE, sin que el backend lo deduzca de los filtros, porque con los
   * mismos filtros las dos pestanas muestran cosas distintas: la primera son los
   * movimientos sueltos de los dos paneles, la segunda el historico de lo ya
   * conciliado. Si se dejara que lo tomara, el boton sobre "Movimientos a
   * conciliar" bajaria el historial y el usuario no se enteraria hasta abrir el
   * archivo.
   */
  private ladoDeExportacion(): LadoReporte {
    return this.tab() === 'conciliados' ? 'CONCILIADOS' : 'PENDIENTES';
  }

  /**
   * Guarda el blob que devolvio el backend con el nombre que puso el.
   *
   * El nombre lo decide el servidor (`Content-Disposition`) y no esta copia. Ahi
   * va el rango de fechas y el lado, para que en la carpeta de descargas se
   * distingan dos exportaciones de la misma semana. Reimplementar esa regla aca
   * seria tenerla en dos lugares: el dia que el backend le anada el nombre de la
   * cuenta, esta pantalla seguiria bajando archivos que se llaman todos igual y
   * nadie sabria cual es cual.
   *
   * Si la cabecera no viniera (un proxy que la saca es el caso comun), se arma
   * uno con los mismos datos. No es perfecto, pero es un archivo con nombre en vez
   * de uno que el navegador llama `download` sin extensión, que es peor.
   */
  private guardarArchivo(resp: HttpResponse<Blob>, nombrePorDefecto: string): void {
    const blob = resp.body;
    if (!blob) {
      // Un 200 sin cuerpo no deberia pasar, pero si pasara, lo que hay que decir es
      // que no se recibio nada, no mostrar un error de JavaScript en blanco.
      this.mensaje.set({ tipo: 'error', texto: 'El servidor respondió sin contenido: no se puede descargar.' });
      return;
    }
    this.descargarBlob(blob, this.nombreDelHeader(resp.headers.get('content-disposition'), nombrePorDefecto));
  }

  /** El mismo nombre que arma el backend, para cuando la cabecera no llegue. */
  private nombreDe(formato: string, f: FiltroConciliacion): string {
    const rango = `${f.desde ?? 'inicio'}-a-${f.hasta ?? 'hoy'}`;
    const lado = this.ladoDeExportacion() === 'CONCILIADOS' ? 'conciliados' : 'pendientes';
    return `conciliacion-${lado}-${rango}.${formato === 'excel' ? 'xlsx' : formato}`;
  }

  /**
   * Saca el nombre de un `Content-Disposition`.
   *
   * Se prefiere la forma `filename*=UTF-8''...` (RFC 5987), que es la que puede
   * traer acentos, y se cae a la forma simple entrecomillada. Se desarma con
   * `split` y no con una regexp laxa porque el valor va entre comillas dobles y
   * adentro puede haber un `;`, que es justo el separador de los parametros: "Juan;
   * Perez.xlsx" partido por `;` da un nombre con la extension perdida y Windows
   * deja de saber que tipo de archivo es.
   */
  private nombreDelHeader(contentDisposition: string | null, porDefecto: string): string {
    if (!contentDisposition) {
      return porDefecto;
    }
    const extensible = /filename\*=(?:UTF-8|utf-8)''([^;]+)/.exec(contentDisposition);
    if (extensible) {
      return decodeURIComponent(extensible[1]);
    }
    const simple = /filename="([^"]*)"/.exec(contentDisposition);
    return simple ? simple[1] : porDefecto;
  }

  /**
   * El CSV, armado en el navegador con los datos que ya estan en memoria.
   *
   * ── POR QUE ESTE Y NO OTRO FORMATO MAS EN EL SERVIDOR ────────────────────────
   *
   * Porque el CSV necesita un BOM (U+FEFF) al principio y un separador ";" en vez
   * de ",". Son dos cosas que hacen falta para que Excel en es-AR no se coma la
   * coma decimal, y en el servidor habria que traer una libreria de CSV o armarlo a
   * mano con el mismo cuidado. En el navegador sale en cuatro lineas.
   *
   * ── LAS COLUMNAS TIENEN QUE SER LAS MISMAS QUE LAS DEL SERVIDOR ──────────────
   *
   * Y antes no lo eran: el CSV de conciliados traia "COMPROBANTE BCO" y "COMPROBANTE
   * CTBLE" en dos columnas, y el .xlsx del servidor trae una sola, "COMPROBANTES",
   * con los dos juntos separados por " / ". Con eso, exportar los dos formatos y
   * compararlos da dos archivos con la misma informacion partida distinto, y la
   * conclusion razonable del usuario es que uno esta roto.
   *
   * El orden y los nombres de columna estan escritos aca a mano y en
   * `ReporteService.java` del backend. Es una duplicacion, y la alternativa
   * (preguntarle al backend las columnas por un endpoint) es mas lio que el ahorro de
   * una columna de un archivo que se arma entero en el servidor. Lo que evita que se
   * separen es que las dos copias estan juntas: `ReporteService.java` y este metodo,
   * y cualquier diferencia se ve en el primer export real que el usuario hace
   * abriendo los dos archivos.
   */
  private exportarCsv(f: FiltroConciliacion): void {
    const q = this.busquedaDeExportacion().toLowerCase();
    // Un solo texto para los dos lados, igual que el servidor. Ver
    // `busquedaDeExportacion`.
    const visiblesBanco = this.banco().filter(
      (m) => !q || m.detalle.toLowerCase().includes(q) || (m.comprobante ?? '').toLowerCase().includes(q),
    );
    const visiblesContable = this.contable().filter(
      (m) => !q || m.concepto.toLowerCase().includes(q) || m.comprobante.toLowerCase().includes(q),
    );

    const filas =
      this.tab() === 'a-conciliar'
        ? [
            ['LADO', 'FECHA', 'DETALLE', 'COMPROBANTE', 'IMPORTE', 'CUENTA', 'ORIGEN'],
            ...visiblesBanco.map((m) => [
              'BANCO',
              m.fecha,
              m.detalle,
              m.comprobante ?? '',
              m.importe.toFixed(2),
              m.cuentaBancaria.banco,
              m.origen,
            ]),
            ...visiblesContable.map((m) => [
              'CONTABLE',
              m.fecha,
              m.concepto,
              m.comprobante,
              m.importe.toFixed(2),
              `${m.cuentaContable.codigo} / ${m.circuito.nombre}`,
              m.origen,
            ]),
          ]
        : [
            ['FECHA', 'DETALLE', 'IMPORTE', 'BANCO', 'CUENTA CONTABLE', 'CIRCUITO', 'COMPROBANTES', 'ESTADO'],
            ...this.conciliados().map((c) => [
              c.fecha,
              c.detalle,
              c.importe.toFixed(2),
              c.cuentaBancaria.banco,
              c.cuentaContable.codigo,
              c.circuito.nombre,
              `${c.movimientoBancario.comprobante ?? ''} / ${c.movimientoContable.comprobante}`,
              c.estado,
            ]),
          ];

    this.descargarCsv(filas, this.nombreDe('csv', f));
  }

  private descargarCsv(filas: string[][], nombre: string): void {
    // El separador es ";" y no "," porque Excel en locale es-AR abre la "," como
    // separador de decimales y rompe todas las columnas con importes.
    const cuerpo = filas.map((f) => f.map(celdaCsv).join(';')).join('\r\n');
    // El BOM se arma por codigo, no escribiendo el caracter invisible en el source.
    // Literal, un editor, un "guardar como" o un copy/paste lo borran sin avisar y el
    // archivo igual se genera bien: solo se rompe cuando alguien lo abre en Excel y ve
    // "COMISIÓN". Ahi ya no queda forma de saber que paso. Con el codigo no hay nada
    // invisible que se pueda perder, y se puede buscar con Ctrl+F.
    const BOM = String.fromCharCode(0xfeff);
    this.descargarBlob(new Blob([BOM + cuerpo], { type: 'text/csv;charset=utf-8' }), nombre);
  }

  /**
   * Dispara la descarga de un blob.
   *
   * El `<a>` no esta en el HTML: se crea, se usa y se descarta. Ponerlo en el
   * template con `href=""` y cambiar el href por codigo obliga a que el elemento
   * exista siempre en la pagina, y en una pantalla con dos Pestañas y cuatro
   * filtros ya hay bastante estado. Asi son cuatro lineas y ningun elemento fantasma.
   */
  private descargarBlob(blob: Blob, nombre: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = nombre;
    a.click();
    // Libera el object URL: si no, el blob queda retenido en memoria hasta que se
    // recargue la pagina, y con un archivo por exportacion eso se nota.
    URL.revokeObjectURL(url);
  }

  // ─── Utilidades de presentacion ───────────────────────────────────────────────

  protected cerrarMensaje(): void {
    this.mensaje.set(null);
  }

  protected cambiarTab(t: Tab): void {
    this.tab.set(t);
  }

  /**
   * Empresa elegida en la barra de arriba.
   *
   * LO QUE todavia NO HACE: cambiar los datos de la pantalla. Los endpoints de la
   * conciliacion no reciben la empresa, asi que siguen leyendo de la cuenta por
   * defecto. Se registra igual, y no se finge que ya surtio efecto: un selector que
   * acepta el clic y no cambia nada es peor que uno que todavia no esta, porque
   * uno hace creer que los datos que se ven son de la empresa elegida.
   *
   * Lo que si hay que resolver aca es el vaciado: al cambiar de empresa, el filtro
   * aplicado quedaria apuntando a catalogos de otra, y "aplicar" traeria un 404
   * imposible de explicar. Por eso el filtro se tira y se avisa.
   */
  protected cambioEmpresa(e: Empresa): void {
    this.empresaActiva.set(e);
    this.filtro.set(null);
    this.banco.set([]);
    this.contable.set([]);
    this.conciliados.set([]);
    this.mensaje.set({
      texto: `Empresa activa: ${e.nombre}. Elegi los filtros de nuevo para ver sus datos.`,
      tipo: 'info',
    });
  }

  /**
   * Traduce el error a un mensaje que diga la verdad.
   *
   * El `detalle` del cuerpo manda sobre cualquier otra cosa: el backend lo manda en el
   * 409 de signo incompatible y ahi es lo unico que explica que paso.
   *
   * Lo que NO alcanza es el status solo, asi que `fallback409` lo pasa cada accion. Un
   * 409 en `conciliar` y un 409 en `autoconciliar` son hechos distintos y el mismo
   * texto para los dos es una de las dos veces mentira.
   */
  private mensajeDe(e: unknown, fallback409 = YA_CONCILIADO): string {
    if (e instanceof HttpErrorResponse) {
      const detalle = (e.error as { detalle?: unknown } | null)?.detalle;
      if (typeof detalle === 'string' && detalle.length > 0) {
        return detalle;
      }
      switch (e.status) {
        case 0:
          return 'No se pudo contactar al servidor.';
        case 400:
          return 'La petición fue inválida.';
        case 404:
          return 'Ese movimiento ya no existe.';
        case 409:
          return fallback409;
        default:
          return `Error ${e.status}.`;
      }
    }
    return 'Error inesperado.';
  }
}

/** 409 de conciliar: alguien se adelantó y la fila ya no está pendiente. */
const YA_CONCILIADO = 'No se pudo conciliar: alguno de los dos movimientos ya fue conciliado o descartado.';

/**
 * 409 de autoconciliar: la fila SIGUE pendiente, lo que no hay es una contraparte con
 * la misma fecha, signo e importe. Por eso no se puede reutilizar YA_CONCILIADO: la
 * fila está a la vista en el panel, y decir que "ya fue conciliada" hace que el
 * usuario busque en el historial algo que no existe.
 */
const SIN_EXACTO =
  'No encontré un movimiento en Xubio con la misma fecha, signo e importe. Buscalo a mano en el panel derecho y conciliá los dos.';

/**
 * Escapa una celda de CSV y la entrecomilla SIEMPRE.
 *
 * Siempre, y no solo cuando hace falta: entrecomillar todo es valido en RFC 4180 y
 * evita el fallo clasico de un detalle que empieza con "COMISION, MANTENIMIENTO",
 * que se desarma en dos columnas al abrirlo. Si despues alguien mete un valor con
 * comillas, el escape de RFC 4180 es duplicarlas, y la celda ya llega entrecomillada
 * asi que no hay que cambiar el exportador entero.
 */
function celdaCsv(valor: string): string {
  return `"${valor.replace(/"/g, '""')}"`;
}
