package at.hl7.fhir.poc.his;

import at.hl7.fhir.poc.his.service.FhirService;
import at.hl7.fhir.poc.his.service.MatrixService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupRunner implements ApplicationRunner {

    private final FhirService fhirService;
    private final MatrixService matrixService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("HIS Application starting up...");

        // Wait a bit for services to be ready
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Initialize sample patient data
        log.info("Initializing sample data...");
        fhirService.initializeSampleData();

        // Connect to Matrix
        log.info("Connecting to Matrix...");
        boolean connected = matrixService.login();
        if (connected) {
            log.info("Successfully connected to Matrix");
        } else {
            log.warn("Failed to connect to Matrix - messages may not be delivered");
        }

        log.info("HIS Application ready!");
    }
}
