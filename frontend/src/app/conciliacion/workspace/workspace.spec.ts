import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Workspace } from './workspace';

/**
 * Los dos menus de la barra de acciones: Importar y Exportar.
 *
 * ── POR QUE ESTOS CASOS Y NO OTROS ─────────────────────────────────────────────
 *
 * Los dos menus se arman con lo que contesta el backend, no con una lista escrita
 * en el HTML. Eso es lo que hay que proteger, porque la falla de esa decision es
 * silenciosa: la pantalla compila, se ve bien, y el menu ofrece archivos que el
 * backend va a rechazar con un 422, o no ofrece un formato que el backend si
 * sabe generar.
 *
 * Ninguno de los dos se veria en un `ng build`. Solo se ven si alguien mira el
 * DOM o mira lo que sale por el cable.
 */
describe('Workspace: menus de Importar y Exportar', () => {
  let fixture: ComponentFixture<Workspace>;
  let http: HttpTestingController;

  const extractores = [
    {
      codigo: 'EXCEL',
      descripcion: 'Planilla de Excel del banco (.xlsx, .xls)',
      origen: 'EXCEL' as const,
      aceptaArchivo: true,
      puedeEjecutarseSolo: false,
      extensiones: ['.xlsx', '.xls'],
    },
    {
      codigo: 'PDF',
      descripcion: 'Extracto en PDF del banco (con capa de texto)',
      origen: 'PDF' as const,
      aceptaArchivo: true,
      puedeEjecutarseSolo: false,
      extensiones: ['.pdf'],
    },
  ];

  const formatos = [
    {
      formato: 'excel',
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      extension: 'xlsx',
      etiqueta: 'Excel (.xlsx)',
    },
    { formato: 'pdf', contentType: 'application/pdf', extension: 'pdf', etiqueta: 'PDF' },
  ];

  /**
   * Contesta los CUATRO pedidos que hace la pantalla al entrar y deja el fixture
   * listo. Los overrides existen para probar el caso de un formato que el backend
   * todavia no tiene: la peticion se contesta una sola vez, al entrar.
   *
   * El cuarto es `/empresas`, el de la barra de empresa. Se contesta con lista
   * vacia, que es el estado de verdad con Xubio apagado. No es solo para que el
   * `verify()` del `afterEach` pase: si esa peticion queda colgada, el fallo dice
   * "Expected no open requests" y el sintoma parece un problema de los menus,
   * cuando lo unico que cambio es que la pantalla consulta que empresas hay.
   */
  async function armar(over: { formatos?: unknown[]; extractores?: unknown[] } = {}): Promise<void> {
    fixture = TestBed.createComponent(Workspace);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);

    http.expectOne((r) => r.url.endsWith('/empresas')).flush([]);
    http.expectOne((r) => r.url.endsWith('/filtros/opciones')).flush({
      cuentasBancarias: [],
      cuentasContables: [],
      circuitos: [],
    });
    http.expectOne((r) => r.url.endsWith('/importaciones/extractores')).flush(over.extractores ?? extractores);
    http.expectOne((r) => r.url.endsWith('/exportaciones/formatos')).flush(over.formatos ?? formatos);
    fixture.detectChanges();
  }

  function botonesDe(menu: 'importar' | 'exportar'): HTMLButtonElement[] {
    const el = fixture.nativeElement as HTMLElement;
    const divs = el.querySelectorAll<HTMLElement>('.dropdown-menu.show');
    const contenedor = menu === 'importar' ? divs[0] : divs[divs.length - 1];
    return Array.from(contenedor?.querySelectorAll('button, label') ?? []);
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Workspace],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  afterEach(() => {
    // Si un test dejo una peticion sin contestar, `verify` lo dice por el. Es mejor
    // que un pedido colgado pase inadvertido y despues el otro test se coma la
    // respuesta.
    http.verify();
  });

  // ─── Importar ─────────────────────────────────────────────────────────────────

  it('el filtro de archivos del menu Importar lo manda el extractor, no esta escrito en el HTML', async () => {
    await armar();
    (fixture.componentInstance as unknown as { menuImportar: { set(v: boolean): void } }).menuImportar.set(true);
    fixture.detectChanges();

    const inputs = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLInputElement>('input[type=file]'),
    );
    expect(inputs.length).toBe(2);

    // El `accept` sale de `extensiones` del extractor. Con la lista vieja
    // hardcodeada (".csv,.txt") estos dos inputs de entrada estarian en gris en el
    // selector de archivos, y no habria forma de subir ni un .xlsx ni un .pdf.
    expect(inputs[0].getAttribute('accept')).toBe('.xlsx,.xls');
    expect(inputs[1].getAttribute('accept')).toBe('.pdf');
  });

  it('un extractor sin extensiones no pone `accept`, que es mejor que un filtro vacio', async () => {
    // Una fuente de API no tiene archivo que filtrar. Con `accept=""` el selector
    // de archivos de Windows no muestra NADA y parece roto. Con el atributo
    // ausente, muestra todos, que es lo correcto.
    await armar({
      extractores: [
        {
          codigo: 'GALICIA_API',
          descripcion: 'API del banco',
          origen: 'API_BANCARIA',
          aceptaArchivo: true,
          puedeEjecutarseSolo: true,
          extensiones: [],
        },
      ],
    });

    (fixture.componentInstance as unknown as { menuImportar: { set(v: boolean): void } }).menuImportar.set(true);
    fixture.detectChanges();

    const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type=file]');
    expect(input).not.toBeNull();
    expect(input!.hasAttribute('accept')).toBe(false);
  });

  it('el menu Importar ofrece solo los dos formatos de archivo, sin CSV ni DEMO', async () => {
    await armar();
    (fixture.componentInstance as unknown as { menuImportar: { set(v: boolean): void } }).menuImportar.set(true);
    fixture.detectChanges();

    const textos = botonesDe('importar').map((b) => b.textContent?.trim() ?? '');
    expect(textos.some((t) => t.includes('Excel'))).toBe(true);
    expect(textos.some((t) => t.includes('PDF'))).toBe(true);
    // CSV y DEMO se fueron del menu. Un archivo CSV se sube ahora por el camino
    // que corresponde, que es guardarlo como Excel, o por PDF.
    expect(textos.some((t) => t.includes('CSV'))).toBe(false);
    expect(textos.some((t) => t.includes('DEMO'))).toBe(false);
  });

  // ─── Exportar ─────────────────────────────────────────────────────────────────

  it('el menu Exportar lista lo que el backend sabe hacer, mas el CSV', async () => {
    await armar();
    (fixture.componentInstance as unknown as { menuExportar: { set(v: boolean): void } }).menuExportar.set(true);
    fixture.detectChanges();

    const textos = botonesDe('exportar').map((b) => b.textContent?.trim());
    expect(textos).toEqual(['Excel (.xlsx)', 'PDF', 'CSV']);
  });

  it('el menu Exportar sigue ofreciendo CSV aunque el backend no conteste', async () => {
    // El CSV se arma en el navegador, no en el servidor. Si /formatos falls, quitar
    // el CSV del menu seria tirar abajo el unico formato que todavia funciona.
    fixture = TestBed.createComponent(Workspace);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);

    http.expectOne((r) => r.url.endsWith('/empresas')).flush([]);
    http.expectOne((r) => r.url.endsWith('/filtros/opciones')).flush({
      cuentasBancarias: [],
      cuentasContables: [],
      circuitos: [],
    });
    http.expectOne((r) => r.url.endsWith('/importaciones/extractores')).flush(extractores);
    http
      .expectOne((r) => r.url.endsWith('/exportaciones/formatos'))
      .error(new ProgressEvent('network error'), { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();

    (fixture.componentInstance as unknown as { menuExportar: { set(v: boolean): void } }).menuExportar.set(true);
    fixture.detectChanges();

    const textos = botonesDe('exportar').map((b) => b.textContent?.trim());
    expect(textos).toEqual(['CSV']);
  });

  it('un formato nuevo en el backend aparece solo en el menu, sin tocar el HTML', async () => {
    // Esta es la prueba de que el menu se arma con la respuesta y no con una lista
    // escrita aca. Si alguien reemplaza la lista por un array fijo en el
    // componente, este test falla.
    await armar({
      formatos: [...formatos, { formato: 'ods', contentType: 'x-ods', extension: 'ods', etiqueta: 'OpenDocument' }],
    });

    (fixture.componentInstance as unknown as { menuExportar: { set(v: boolean): void } }).menuExportar.set(true);
    fixture.detectChanges();

    const textos = botonesDe('exportar').map((b) => b.textContent?.trim());
    expect(textos).toEqual(['Excel (.xlsx)', 'PDF', 'OpenDocument', 'CSV']);
  });

  it('los dos menus no pueden quedar abiertos al mismo tiempo', async () => {
    await armar();
    const c = fixture.componentInstance as unknown as {
      menuImportar: { set(v: boolean): void };
      menuExportar: { set(v: boolean): void };
    };

    c.menuImportar.set(true);
    c.menuExportar.set(false);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.dropdown-menu.show').length).toBe(1);

    c.menuExportar.set(true);
    c.menuImportar.set(false);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.dropdown-menu.show').length).toBe(1);
  });
});

/**
 * Que un fallo de catalogo se vea como un fallo y no como una lista vacia.
 *
 * ── POR QUE ESTE CASO NECESITA SU PROPIO ARCHIVO ────────────────────────────────
 *
 * Porque es la unica parte de la pantalla donde la diferencia se ve en la cara
 * del usuario, y en ninguna de las dos versiones el DOM se ve igual de raro: un
 * desplegable con cero opciones. En el error se lee el motivo; en el "no tenes
 * cuentas cargadas" no se lee nada. Ningun snapshot ni una foto de pantalla
 *orphic lo distingue, asi que hace falta un assert explicito sobre el texto.
 *
 * ── QUE SE ESTA PROBANDO ───────────────────────────────────────────────────────
 *
 * El backend responde 503 con {error, detalle, reintentable} cuando no pudo leer
 * los catalogos de Xubio. Esto verifica que:
 *  1. el texto del `detalle` aparece en la pantalla, y
 *  2. el resto de la pantalla sigue computando (los menus se arman) en vez de
 *     cortarse el error.
 *
 * El punto 2 importa. El error de los catalogos no puede dejar la pantalla
 * a medio cargar: si al ver el 503 el componente deja de pedir extractores y
 * formatos, un problema de Xubio se convierte en un problema de la app entera,
 * y arreglar Xubio no alcanza porque el estado del navegador quedo a medias.
 */
describe('Workspace: fallo al leer los catalogos', () => {
  let fixture: ComponentFixture<Workspace>;
  let http: HttpTestingController;

  const extractores = [
    {
      codigo: 'EXCEL',
      descripcion: 'Planilla de Excel del banco (.xlsx, .xls)',
      origen: 'EXCEL' as const,
      aceptaArchivo: true,
      puedeEjecutarseSolo: false,
      extensiones: ['.xlsx', '.xls'],
    },
  ];

  // El backend NO devuelve CSV: es el unico formato que se arma en el navegador.
  // Por eso la lista del server va vacia, y el unico CSV que puede aparecer en el
  // menu es el que agrega el componente.
  const formatos: unknown[] = [];

  /** El 503 que devuelve el backend cuando Xubio no se puede leer. */
  const ERROR_XUBIO = {
    error: 'CATALOGO_NO_DISPONIBLE',
    detalle: 'Xubio esta habilitado pero faltan las credenciales. Faltan client-id o client-secret.',
    reintentable: false,
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Workspace],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  afterEach(() => {
    http.verify();
  });

  it('muestra el motivo del fallo y no una lista vacia sin explicar', async () => {
    fixture = TestBed.createComponent(Workspace);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);

    http.expectOne((r) => r.url.endsWith('/empresas')).flush([]);
    http
      .expectOne((r) => r.url.endsWith('/filtros/opciones'))
      .flush(ERROR_XUBIO, { status: 503, statusText: 'Service Unavailable' });
    http.expectOne((r) => r.url.endsWith('/importaciones/extractores')).flush(extractores);
    http.expectOne((r) => r.url.endsWith('/exportaciones/formatos')).flush(formatos);
    fixture.detectChanges();

    const texto = (fixture.nativeElement as HTMLElement).textContent ?? '';

    // El `detalle` del backend, no un "algo fallo" generico. El backend ya sabe
    // distinguir "faltan credenciales" de "dio timeout" de "no respondio el
    // token", y tira esa informacion a la basura si el front no la muestra.
    expect(texto).toContain('faltan las credenciales');

    // Y que no aparezca un error generico que no dice nada util, ni un literal
    // "undefined" de un template mal armado.
    expect(texto).not.toContain('undefined');
  });

  it('el menu Exportar se sigue armando aunque fallen los catalogos', async () => {
    fixture = TestBed.createComponent(Workspace);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);

    http.expectOne((r) => r.url.endsWith('/empresas')).flush([]);
    http
      .expectOne((r) => r.url.endsWith('/filtros/opciones'))
      .flush(ERROR_XUBIO, { status: 503, statusText: 'Service Unavailable' });
    http.expectOne((r) => r.url.endsWith('/importaciones/extractores')).flush(extractores);
    http.expectOne((r) => r.url.endsWith('/exportaciones/formatos')).flush(formatos);
    fixture.detectChanges();

    // OJO sobre lo que este test prueba y lo que no. Prueba que la pantalla
    // SIGUE COMPUTANDO el menu, o sea que el error de los catalogos no cortó la
    // carga del resto. NO prueba que los botones se puedan apretar: en la pantalla
    // real estan deshabilitados hasta que se aplica un filtro, que es lo correcto
    // (para importar hay que elegir en que cuenta, y sin cuentas no hay filtro).
    // Ese deshabilitado es de antes de esto y no tiene nada que ver con Xubio.
    (fixture.componentInstance as unknown as { menuExportar: { set(v: boolean): void } }).menuExportar.set(true);
    fixture.detectChanges();

    const divs = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.dropdown-menu.show');
    const textos = Array.from(divs[divs.length - 1]?.querySelectorAll('button, label') ?? []).map((b) =>
      b.textContent?.trim(),
    );
    expect(textos).toEqual(['CSV']);
  });
});
