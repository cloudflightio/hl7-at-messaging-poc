package at.hl7.fhir.poc.gp;

import at.hl7.fhir.poc.gp.service.MatrixService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupRunner implements ApplicationRunner {

    private final MatrixService matrixService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("GP Application starting up...");

        // Wait a bit for services to be ready
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Connect to Matrix
        log.info("Connecting to Matrix...");
        boolean connected = matrixService.login();
        if (connected) {
            log.info("Successfully connected to Matrix - listening for messages");
        } else {
            log.warn("Failed to connect to Matrix - will retry polling");
        }

        log.info("GP Application ready!");
    }
}
