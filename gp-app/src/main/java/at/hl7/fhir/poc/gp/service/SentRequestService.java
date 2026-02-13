package at.hl7.fhir.poc.gp.service;

import at.hl7.fhir.poc.gp.model.SentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service for tracking sent CommunicationRequest messages.
 */
@Service
@Slf4j
public class SentRequestService {

    private final List<SentRequest> sentRequests = Collections.synchronizedList(new ArrayList<>());

    public void addSentRequest(SentRequest request) {
        sentRequests.add(request);
        log.info("Tracked sent request with bundle ID: {}", request.getBundleId());
    }

    public SentRequest getRequestById(String id) {
        synchronized (sentRequests) {
            return sentRequests.stream()
                    .filter(r -> r.getId().equals(id))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Finds a sent request by its original bundle ID.
     * This is used to link responses back to the original request.
     *
     * @param bundleId The original bundle ID (may be with or without urn:uuid: prefix)
     * @return The matching SentRequest, or null if not found
     */
    public SentRequest getRequestByBundleId(String bundleId) {
        if (bundleId == null) {
            return null;
        }

        // Normalize the bundle ID (remove urn:uuid: prefix if present)
        String normalizedId = bundleId.startsWith("urn:uuid:")
                ? bundleId.substring(9)
                : bundleId;

        synchronized (sentRequests) {
            return sentRequests.stream()
                    .filter(r -> {
                        String reqBundleId = r.getBundleId();
                        if (reqBundleId == null) return false;
                        String normalizedReqId = reqBundleId.startsWith("urn:uuid:")
                                ? reqBundleId.substring(9)
                                : reqBundleId;
                        return normalizedReqId.equals(normalizedId);
                    })
                    .findFirst()
                    .orElse(null);
        }
    }
}
