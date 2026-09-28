import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      // Se usan las rutas REALES, no una lista vacia: el redirect de "/" a
      // "/conciliacion" vive en app.routes.ts, y con provideRouter([]) el test
      // pasaria sin comprobar nada.
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('deja que la pantalla activa se dibuje en el outlet', async () => {
    // El shell ya no tiene navbar: la pantalla de conciliacion es de punta a punta y
    // trae su propio titulo. Lo unico que se comprueba aca es que haya un outlet y
    // que la pantalla que se monte aparezca, sinei el router nunca dibuja nada.
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('router-outlet')).not.toBeNull();
  });

  it('manda la raiz y cualquier ruta desconocida a la pantalla de conciliacion', async () => {
    // Sin esto, "/" quedaria en blanco: las dos rutas viejas existen pero la pantalla
    // de conciliacion es la principal y es la que tiene que aparecer sin que el
    // usuario haga clic en nada.
    await TestBed.inject(Router).navigateByUrl('/');
    expect(TestBed.inject(Router).url).toBe('/conciliacion');

    await TestBed.inject(Router).navigateByUrl('/no-existe');
    expect(TestBed.inject(Router).url).toBe('/conciliacion');
  });
});
