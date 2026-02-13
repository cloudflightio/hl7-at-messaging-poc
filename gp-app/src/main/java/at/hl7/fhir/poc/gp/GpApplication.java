package at.hl7.fhir.poc.gp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GpApplication {

    public static void main(String[] args) {
        SpringApplication.run(GpApplication.class, args);
    }
}
