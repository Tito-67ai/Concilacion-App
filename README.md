ConciliacionApp - demo de conciliacion bancaria/contable

Backend Spring Boot (Java 17, H2 en memoria, sin base externa ni config).

== 1) ARRANCAR ==
En la carpeta del proyecto (Consejos: cerrar OneDrive si pide):

    .\mvnw.cmd -q -B spring-boot:run

Espera a ver en consola:
    Started ConciliacionAppApplication ... Tomcat started on port(s) 8080

== 2) VER EN EL NAVEGADOR ==
   a) Consola H2 (tablas + datos del seed):
        http://localhost:8080/h2-console
        JDBC URL: jdbc:h2:mem:conciliacion   |   User: sa   |   Password: (vacio)
   b) REST (JSON de los pendientes):
        http://localhost:8080/api/conciliaciones/pendientes   (GET)
      Autoconciliar uno:
        POST http://localhost:8080/api/conciliaciones/{id}/autoconciliar

== 3) QUE MUESTRA LA DEMO (seed) ==
- b1 (20/09 +152000 VENTA): tiene par contable exacto -> al POST se autoconcilia y desaparece de pendientes.
- b2 (19/09 -48000 PAGO PROVEEDOR): sin par -> queda PENDIENTE hasta decision manual.
- Resultado: pendientes 2 -> 1 tras autoconciliar.

== 4) CERRAR ==
Ctrl+C en la consola (o: Get-Process java | Stop-Process)

== Stack ==
Spring Boot 3.4, Java 17, Spring Data JPA, H2 en memoria, H2 Console.
Frontend (Angular): en construccion.
