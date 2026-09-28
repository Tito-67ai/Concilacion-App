import { defineConfig } from 'vitest/config';

/**
 * Pool "threads" en vez del default "forks".
 *
 * Con "forks" (child_process) el arranque del worker tardaba mas que el timeout
 * interno de vitest en esta maquina y la corrida terminaba con:
 *   [vitest-pool]: Failed to start forks worker ... Timeout waiting for worker
 * y ningun test llegaba a ejecutarse. Con "threads" (worker_threads) el arranque
 * es casi instantaneo y los 6 tests corren a la primera.
 *
 * Para 2 archivos de test no se pierde nada en aislamiento.
 *
 * ── Si vuelve a fallar con "Timeout waiting for worker to respond" ─────────────
 * No es un problema de configuracion: es CPU. Con el backend Spring corriendo en
 * background (ocupa un nucleo entero con `show-sql: true` escribiendo en boot.log)
 * el worker no llega a responder dentro del timeout y los tests NO fallan: no
 * llegan a arrancar, y el reporte no dice nada de este proyecto. Se vio el 28/09
 * con el boot del :8081 en curso; parado el backend, 6/6 en 27s. Parar el backend
 * antes de correr los tests.
 */
export default defineConfig({
  test: {
    pool: 'threads',
  },
});
