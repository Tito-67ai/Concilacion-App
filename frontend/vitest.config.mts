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
 */
export default defineConfig({
  test: {
    pool: 'threads',
  },
});
