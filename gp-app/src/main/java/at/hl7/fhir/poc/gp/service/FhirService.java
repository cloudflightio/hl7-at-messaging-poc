package at.hl7.fhir.poc.gp.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FhirService {

    @Value("${fhir.server.url}")
    private String fhirServerUrl;

    private FhirContext fhirContext;
    private IGenericClient fhirClient;

    @PostConstruct
    public void init() {
        fhirContext = FhirContext.forR5();
        fhirContext.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        fhirContext.getRestfulClientFactory().setConnectTimeout(60000);
        fhirContext.getRestfulClientFactory().setSocketTimeout(60000);
        fhirClient = fhirContext.newRestfulGenericClient(fhirServerUrl);

        log.info("FHIR client initialized for server: {}", fhirServerUrl);
    }

    public Bundle getBundle(String id) {
        try {
            return fhirClient.read()
                    .resource(Bundle.class)
                    .withId(id)
                    .execute();
        } catch (Exception e) {
            log.error("Error fetching bundle {}", id, e);
            return null;
        }
    }

    public Bundle saveBundle(Bundle bundle) {
        try {
            var outcome = fhirClient.create()
                    .resource(bundle)
                    .execute();
            return (Bundle) outcome.getResource();
        } catch (Exception e) {
            log.error("Error saving bundle", e);
            return null;
        }
    }

    public String serializeResource(Resource resource) {
        return fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(resource);
    }

    public Resource parseResource(String json) {
        return (Resource) fhirContext.newJsonParser().parseResource(json);
    }
}
