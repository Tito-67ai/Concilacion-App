# Conciliacion.App — Frontend Angular

Pantalla de movimientos bancarios pendientes, para la demo de conciliacion
automatica (el backend es el monolito Spring Boot `conciliacion-app`).

Angular 22.2.0 · zoneless · standalone · signals · vitest

---

## 1) Como se levanta

Son **dos procesos independientes**, uno por lado:

| Proceso | Donde corre | Puerto | Comando |
|---|---|---|---|
| Backend (Spring Boot) | Eclipse | **8081** | Run Spring Boot |
| Frontend (Angular) | VS Code | 4200 | `npm start` |

Despues abrir <http://localhost:4200>.

Importante: el backend **no** corre en 8080. Se fijo 8081 a proposito porque el
8080 suele estar ocupado. Si lo cambias, cambialo tambien en
`proxy.conf.json`.

---

## 2) Por que hay un proxy

El frontend llama a `/api/conciliaciones/...` (ruta relativa, sin host). El dev
server de Angular reenvia eso al backend:

```
navegador  ──►  localhost:4200/api/...
                        │
                        └─ proxy.conf.json ──► localhost:8081/api/...
```

Como la peticion es *same-origin*, el navegador **nunca evalua CORS**. Por eso
el `CorsConfig.java` del backend no hace falta para desarrollo y se puede
borrar (conviene dejarlo gateado por perfil si se deploya).

Bonus: para cambiar de puerto o de maquina se toca **una sola linea**
(`proxy.conf.json`), no el codigo de la app.

La consola H2 no pasa por el proxy: abrirla directo en
<http://localhost:8081/h2-console> (`jdbc:h2:mem:conciliacion`, user `sa`, sin
password).

---

## 3) Estructura

```
src/app/
  app.ts / app.html / app.css      shell, monta <app-pendientes />
  app.config.ts                    LOCALE_ID es-AR + provideHttpClient
  app.routes.ts                    rutas (vacio por ahora)
  conciliacion/
    conciliacion.model.ts          contrato con la API (tipos union)
    conciliacion.service.ts        HttpClient, URL relativa
    fecha-dm.pipe.ts               "2026-09-20" -> "20/09/2026"
    pendientes/
      pendientes.ts                componente con signals
      pendientes.html
      pendientes.css
```

Los archivos se nombran sin sufijo `.component` / `.service` porque es la
convencion que genera el CLI desde Angular 20. Asi `ng g c algo` no crea
duplicados.

---

## 4) Detalles que no son obvios

**Zoneless.** Este proyecto no tiene `zone.js`. La change detection no se
dispara sola cuando muta un campo comun dentro de un `subscribe` de RxJS, asi
que **el estado va en signals**. Copiar la version con `pendientes: Movimiento[]`
y `this.pendientes = data` compila sin errores y deja la tabla permanentemente
vacia.

**Locale `es-AR`.** `registerLocaleData` en `app.config.ts` es obligatorio:
sin eso Angular tira `Missing locale data for the locale id "es-AR"`. Con eso,
el pipe `number` imprime `152.000,00` y no `152,000.00`.

**Fechas.** No usar `| date` con el `yyyy-MM-dd` que manda Jackson: Angular lo
parsea como UTC y en Argentina (UTC-3) la fila del 20/09 se muestra como
19/09. Por eso esta `FechaDmPipe`, que arma el texto con `split` y no toca
`Date`. Hay un test que cubre exactamente ese caso.

**Contrato de `autoconciliar`.** Hoy el backend responde `200` con body `null`
cuando no encuentra par, asi que `null` significa *"no pudo conciliar"*, no
*"no existe"*. El mensaje del componente esta escrito en esos terminos a
proposito. Cuando el backend pase a `404` / `409`, se saca el `| null` del
servicio y se ajusta el texto.

**Sin columna "Estado".** El endpoint `/pendientes` ya filtro por `PENDIENTE`,
as que esa columna siempre decia lo mismo. Se elimino.

---

## 5) Tests

```bash
npm test
```

- `fecha-dm.pipe.spec.ts`: logica pura, incluido el caso off-by-one de timezone.
- `app.spec.ts`: smoke test del shell.

---

## 6) Que falta

- **No hay matching.** La pantalla muestra pendientes y un boton que o concilia
  o no hace nada en silencio. Lo que falta es la pantalla de matching: el
  movimiento bancario al lado de sus candidatos contables, con diferencias a la
  vista y aceptar/rechazar. Es la pieza que justifica el producto.
- **Sin paginacion ni filtros** (por CBU, rango de fechas).
- **Sin manejo de errores de red profundo** (reintentos, timeout).
