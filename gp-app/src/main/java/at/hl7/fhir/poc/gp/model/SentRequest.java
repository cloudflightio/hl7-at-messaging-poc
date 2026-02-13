package at.hl7.fhir.poc.gp.model;

import lombok.Data;

import java.util.Date;

/**
 * Model for tracking sent CommunicationRequest messages.
 */
@Data
public class SentRequest {
    private String id;
    private Date sentAt;

    // Bundle info
    private String bundleId;           // The original bundle ID (used for response linking)
    private String fhirBundleId;       // FHIR server assigned ID
    private String bundleJson;

    // Patient info
    private String patientName;
    private String patientBirthDate;
    private String patientId;

    // Request info
    private String description;
    private String status;

    // Recipient info
    private String recipientName;
}
