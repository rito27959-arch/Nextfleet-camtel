package cm.camtel.nextfleet.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Point d'entrée de l'API NexFleet (monolithe modulaire, chap. 12 du cahier V4). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NextfleetApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(NextfleetApiApplication.class, args);
    }
}
