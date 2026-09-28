import { FechaDmPipe } from './fecha-dm.pipe';

describe('FechaDmPipe', () => {
  const pipe = new FechaDmPipe();

  it('pasa yyyy-MM-dd a dd/MM/yyyy', () => {
    expect(pipe.transform('2026-09-20')).toBe('20/09/2026');
  });

  // Si alguien reemplaza este pipe por | date, este test es el que avisa:
  // DatePipe parsea como UTC y en Argentina devuelve el dia anterior.
  it('no corre la fecha un dia por timezone', () => {
    expect(pipe.transform('2026-01-01')).toBe('01/01/2026');
    expect(pipe.transform('2026-12-31')).toBe('31/12/2026');
  });

  it('devuelve vacio ante null o undefined', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
  });

  it('devuelve el input tal cual si no parece una fecha', () => {
    expect(pipe.transform('no-es-fecha')).toBe('no-es-fecha');
  });
});
