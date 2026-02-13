package at.hl7.fhir.poc.his.model;

import lombok.Data;

import java.util.Date;

/**
 * Model for received CommunicationRequest data from GP.
 */
@Data
public class ReceivedRequest {
    private String id;
    private Date receivedAt;

    // MessageHeader info
    private String eventCode;
    private String sourceName;
    private String sourceSoftware;
    private String sourceVersion;
    private String sourceContact;

    // Patient info
    private String patientName;
    private String patientBirthDate;
    private String patientId;

    // Request info
    private String requestDescription;
    private String requestStatus;
    private String requestPriority;
    private Date requestAuthoredOn;

    // Sender/Requester info
    private String requesterName;

    // Recipient info
    private String recipientName;

    // Bundle reference
    private String fhirBundleId;
    private String bundleJson;

    // Original message bundle ID (for response linking)
    private String originalMessageBundleId;
}
