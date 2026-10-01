import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ConciliacionService } from '../conciliacion.service';
import { Empresa } from '../conciliacion.model';

/**
 * Barra de empresa activa.
 *
 * QUE REPRESENTA UNA EMPRESA ACA: una App Cliente de Xubio. La API publica de
 * Xubio no tiene el concepto de "estudio" ni de "empresas de un usuario": son
 * 63 rutas, todas con oauth2 `client_credentials`, y cada par client-id/secret
 * pertenece a UNA sola empresa. Por eso una empresa es, aca, un par de
 * credenciales mas un nombre, y el nombre NO se escribe a mano: lo trae
 * `GET /miempresa` de Xubio. Si el nombre se tipeara, en tres meses la lista
 * mentiria respecto de lo que Xubio cree que es la empresa.
 *
 * POR QUE NO HAY UN "LOGIN" ACA: el login con usuario y password pertenece a la
 * app web de Xubio, no a su API. Habria que reconstruir una API que Xubio no
 * publica, guardar la contrasena del usuario, y aceptar que puede dejar de
 * andar sin aviso. Con una App Cliente por empresa eso no hace falta: la
 * revocacion se hace desde Xubio, en un lugar que el usuario ya conoce.
 *
 * SE PERSISTE LA ELECCION: el backend no tiene sesion, asi que si la eleccion no
 * se guarda, recargar la pagina vuelve a la primera empresa. Eso hace creer al
 * usuario que cambio de empresa sin haberlo hecho.
 *
 * OJO CON SENALES: el proyecto es zoneless. Un campo comun (`empresas = []` +
 * `this.empresas = data`) COMPILARIA y la lista quedaria vacia para siempre, sin
 * dar error. Por eso el estado va en signals.
 */
const CLAVE_EMPRESA = 'conciliacion.empresa-activa';

@Component({
  selector: 'app-selector-empresa',
  imports: [FormsModule],
  templateUrl: './selector-empresa.html',
  styleUrl: './selector-empresa.css',
})
export class SelectorEmpresa implements OnInit {
  private readonly service = inject(ConciliacionService);

  /** Se emite solo cuando la persona elige, no en la carga inicial. */
  readonly cambio = output<Empresa>();

  protected readonly empresas = signal<Empresa[]>([]);
  protected readonly clave = signal<string | null>(null);
  protected readonly cargando = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly activa = computed<Empresa | null>(
    () => this.empresas().find((e) => e.clave === this.clave()) ?? null,
  );

  /**
   * Cuantas entradas quedaron sin acceso. Se muestra aparte del desplegable y NO
   * se las saca de la lista: una empresa con credenciales vencidas tiene que verse
   * igual, porque si desaparece de la lista uno cree que la borro.
   */
  protected readonly sinAcceso = computed<Empresa[]>(() => this.empresas().filter((e) => e.estado !== 'OK'));

  ngOnInit(): void {
    this.cargar();
  }

  protected reintentar(): void {
    this.cargar();
  }

  protected alElegir(nueva: string): void {
    const elegida = this.empresas().find((e) => e.clave === nueva);
    if (!elegida || elegida.estado !== 'OK') {
      return;
    }
    this.clave.set(nueva);
    localStorage.setItem(CLAVE_EMPRESA, nueva);
    this.cambio.emit(elegida);
  }

  private cargar(): void {
    this.cargando.set(true);
    this.error.set(null);
    this.service.getEmpresas().subscribe({
      next: (lista) => {
        this.empresas.set(lista);
        this.cargando.set(false);
        this.restaurar(lista);
      },
      error: (e: HttpErrorResponse) => {
        this.cargando.set(false);
        this.error.set(detalleDe(e));
      },
    });
  }

  /**
   * Vuelve a dejar como activa la ultima elegida. Si ya no esta en la lista (esa
   * empresa se saco de la configuracion), cae a la primera que responde bien, y no
   * a la primera de la lista: elegir de entrada una empresa sin acceso deja toda la
   * app sin catalogos y no queda claro por que.
   */
  private restaurar(lista: Empresa[]): void {
    const guardada = localStorage.getItem(CLAVE_EMPRESA);
    if (guardada && lista.some((e) => e.clave === guardada && e.estado === 'OK')) {
      this.clave.set(guardada);
      return;
    }
    const inicial = lista.find((e) => e.estado === 'OK') ?? lista[0];
    if (inicial) {
      this.clave.set(inicial.clave);
      localStorage.setItem(CLAVE_EMPRESA, inicial.clave);
    }
  }
}

/**
 * Un error de RED, no de empresa.
 *
 * `/empresas` devuelve 200 aunque empresas individuales fallen: cada una viene con
 * `estado = SIN_ACCESO` y su motivo, y eso lo muestra el componente. Aca se llega
 * solo si el backend no respondio o devolvio algo que no es una lista, y el cuerpo
 * trae {error, detalle, reintentable} igual que el de los catalogos.
 *
 * Se muestra el `detalle`: "no se pudieron consultar las empresas" sin el motivo
 * deja a la persona sin saber si es una credencial mala o Xubio caido.
 */
function detalleDe(e: unknown): string {
  const body = (e as HttpErrorResponse | null)?.error as { detalle?: string } | string | null | undefined;
  if (body && typeof body === 'object' && body.detalle) {
    return body.detalle;
  }
  if (typeof body === 'string' && body.trim()) {
    return body;
  }
  return 'No se pudo consultar la lista de empresas.';
}
