import { registerLocaleData } from '@angular/common';
import localeEsAR from '@angular/common/locales/es-AR';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { ApplicationConfig, LOCALE_ID, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';

// Sin esto Angular tira "Missing locale data for the locale id es-AR" apenas
// se usa un pipe (number/date). registerLocaleData es obligatorio para todo
// locale que no sea en-US.
registerLocaleData(localeEsAR, 'es-AR');

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withFetch()),
    // Sin esto el pipe number imprime 152,000.00 (en-US) en vez de 152.000,00.
    { provide: LOCALE_ID, useValue: 'es-AR' },
  ],
};
