import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Empresa } from '../conciliacion.model';
import { SelectorEmpresa } from './selector-empresa';

/**
 * La barra de empresa activa.
 *
 * ── POR QUE ESTOS CASOS ───────────────────────────────────────────────────────
 *
 * Porque el componente no tiene logica de negocio, pero tiene TRES estados que en
 * el DOM se ven iguales si no se miran de cerca, y uno de ellos es una mentira:
 *
 *   cargando    spinner
 *   error       el `detalle` del 503 + Reintentar
 *   vacio       "Ninguna empresa configurada"
 *   lista       el desplegable
 *
 * "Sin empresas" y "Xubio caido" se ven igual en una foto de pantalla. Uno deja
 * al usuario reconfigurando credenciales que ya estaban bien, y el otro mira la
 * configuracion de Xubio cuando el problema es la red.
 *
 * Y el caso de la empresa SIN ACCESO importa mas de lo que parece: si se sacara de
 * la lista, una credencial vencida se veria como una empresa borrada.
 */
describe('SelectorEmpresa', () => {
  let fixture: ComponentFixture<SelectorEmpresa>;
  let http: HttpTestingController;

  // Con tilde a proposito: los nombres de empresa vienen con acentos y signos, y lo
  // que se verifica aca es que la barra muestre EXACTAMENTE lo que dijo la API. Si el
  // nombre estuviera forjado en el template, el test pasaria igual y no probaria nada.
  const OK: Empresa = { clave: 'tito', nombre: 'Ferretería Tito S.R.L.', estado: 'OK', detalle: null };
  const ROTA: Empresa = {
    clave: 'kiosco',
    nombre: 'Kiosco 24hs',
    estado: 'SIN_ACCESO',
    detalle: "Xubio rechazo el token de 'kiosco' (400).",
  };

  function texto(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function opcionada(): HTMLOptionElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('option'));
  }

  beforeEach(async () => {
    // El componente guarda la eleccion en localStorage. Sin limpiarlo, la eleccion
    // de un test se filtra al siguiente y estos tests pasan por el motivo equivocado.
    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [SelectorEmpresa],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(SelectorEmpresa);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('con Xubio apagado dice que no hay ninguna configurada, y no muestra un desplegable vacio', () => {
    fixture.detectChanges();
    http.expectOne((r) => r.url.endsWith('/empresas')).flush([]); // lo que devuelve el backend apagado
    fixture.detectChanges();

    expect(texto()).toContain('Ninguna empresa configurada');
    // Un desplegable con cero opciones parece un bug. Mejor que no exista.
    expect(opcionada()).toHaveLength(0);
  });

  it('muestra el nombre que trae la API, no uno escrito en el template', () => {
    fixture.detectChanges();
    http.expectOne((r) => r.url.endsWith('/empresas')).flush([OK]);
    fixture.detectChanges();

    expect(opcionada().map((o) => o.textContent?.trim())).toEqual(['Ferretería Tito S.R.L.']);
  });

  it('una empresa sin acceso sigue en la lista, marcada y no seleccionable', () => {
    fixture.detectChanges();
    http.expectOne((r) => r.url.endsWith('/empresas')).flush([OK, ROTA]);
    fixture.detectChanges();

    const opciones = opcionada();
    // NO se filtra: si desapareciera, un secreto vencido pareceria una empresa dada
    // de baja y el usuario iria a mirar Xubio en vez de la app.
    expect(opciones).toHaveLength(2);
    expect(opciones[1].textContent).toContain('Kiosco 24hs');
    expect(opciones[1].textContent).toContain('sin acceso');
    expect(opciones[1].disabled).toBe(true);
    // Y se dice cuantas son, que es la informacion accionable.
    expect(texto()).toContain('1 sin acceso');
  });

  it('ante un 503 muestra el motivo y no un "algo fallo"', () => {
    fixture.detectChanges();
    http.expectOne((r) => r.url.endsWith('/empresas')).flush(
      { error: 'CATALOGO_NO_DISPONIBLE', detalle: 'Xubio no respondio a tiempo (10 s).', reintentable: true },
      { status: 503, statusText: 'Service Unavailable' },
    );
    fixture.detectChanges();

    // El `detalle` del backend, que es lo unico que dice si son credenciales, red o
    // un timeout. Un "no se pudo consultar" generico deja al usuario sin nada que hacer.
    expect(texto()).toContain('Xubio no respondio a tiempo');
    expect(opcionada()).toHaveLength(0);
  });

  it('Reintentar vuelve a pedir la lista', () => {
    fixture.detectChanges();
    http.expectOne((r) => r.url.endsWith('/empresas')).flush(
      { detalle: 'caido' },
      { status: 503, statusText: 'Service Unavailable' },
    );
    fixture.detectChanges();

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.empresa-reintentar')!.click();
    fixture.detectChanges();

    // El segundo pedido esta en vuelo: por eso `verify` no protesta en el afterEach.
    http.expectOne((r) => r.url.endsWith('/empresas')).flush([OK]);
    fixture.detectChanges();
    expect(opcionada()).toHaveLength(1);
  });

  it('la empresa elegida queda guardada, para que recargar no la pierda', () => {
    fixture.detectChanges();
    http.expectOne((r) => r.url.endsWith('/empresas')).flush([OK]);
    fixture.detectChanges();

    // El backend no tiene sesion. Sin esto, recargar la pagina vuelve a la primera
    // empresa y el usuario cree que cambio de empresa sin haberlo hecho.
    expect(localStorage.getItem('conciliacion.empresa-activa')).toBe('tito');
  });

  it('vuelve a la ultima elegida, y si ya no existe cae a la primera que responde', () => {
    localStorage.setItem('conciliacion.empresa-activa', 'kiosco');

    fixture.detectChanges();
    // 'kiosco' quedo con SIN_ACCESO: elegirla de entrada dejaria toda la app sin
    // catalogos, y no quedaria claro por que.
    http.expectOne((r) => r.url.endsWith('/empresas')).flush([OK, ROTA]);
    fixture.detectChanges();

    expect((fixture.componentInstance as unknown as { clave(): string | null }).clave()).toBe('tito');
  });
});
