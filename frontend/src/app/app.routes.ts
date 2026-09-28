import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: 'pendientes', loadComponent: () => import('./conciliacion/pendientes/pendientes').then((m) => m.Pendientes) },
  { path: 'conciliados', loadComponent: () => import('./conciliacion/conciliados/conciliados').then((m) => m.Conciliados) },
  { path: '', pathMatch: 'full', redirectTo: 'pendientes' },
  { path: '**', redirectTo: 'pendientes' },
];
