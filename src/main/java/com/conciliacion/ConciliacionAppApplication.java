package com.conciliacion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * @ConfigurationPropertiesScan esta aca y no sobre cada clase porque BancoConfiguracion
 * se bindea a `conciliacion.bancos.*`. Sin el scan, ese bloque del application.yml
 * se ignora en silencio: los extractores quedan todos habilitados y no hay forma de
 * apagar uno sin tocar codigo.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ConciliacionAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConciliacionAppApplication.class, args);
    }
}
