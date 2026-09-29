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
   * Contesta los tres pedidos que hace la pantalla al entrar y deja el fixture
   * listo. Los overrides existen para probar el caso de un formato que el backend
   * todavia no tiene: la peticion se contesta una sola vez, al entrar.
   */
  async function armar(over: { formatos?: unknown[]; extractores?: unknown[] } = {}): Promise<void> {
    fixture = TestBed.createComponent(Workspace);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);

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
