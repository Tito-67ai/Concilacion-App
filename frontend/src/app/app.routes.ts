import { Routes } from '@angular/router';

/**
 * La pantalla de conciliacion (filtros + dos paneles) es la pantalla principal y
 * entra por "/". Las dos viejas quedan debajo, no borradas: `/pendientes` y
 * `/conciliados` siguen funcionando, asi que un link viejo o un ejemplo en un mail
 * no rompe.
 */
export const routes: Routes = [
  { path: 'conciliacion', loadComponent: () => import('./conciliacion/workspace/workspace').then((m) => m.Workspace) },
  { path: 'pendientes', loadComponent: () => import('./conciliacion/pendientes/pendientes').then((m) => m.Pendientes) },
  { path: 'conciliados', loadComponent: () => import('./conciliacion/conciliados/conciliados').then((m) => m.Conciliados) },
  { path: '', pathMatch: 'full', redirectTo: 'conciliacion' },
  { path: '**', redirectTo: 'conciliacion' },
];
