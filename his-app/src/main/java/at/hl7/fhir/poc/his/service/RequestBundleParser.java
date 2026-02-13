package at.hl7.fhir.poc.his.service;

import at.hl7.fhir.poc.his.model.ReceivedRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.*;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parses incoming CommunicationRequest bundles from GP.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RequestBundleParser {

    private final FhirService fhirService;
    private final List<ReceivedRequest> receivedRequests = Collections.synchronizedList(new ArrayList<>());

    /**
     * Parses a received FHIR message bundle and extracts CommunicationRequest data.
     */
    public void parseAndSaveBundle(String bundleJson) {
        try {
            Resource resource = fhirService.parseResource(bundleJson);

            if (!(resource instanceof Bundle bundle)) {
                log.warn("Received resource is not a Bundle");
                return;
            }

            if (bundle.getType() != Bundle.BundleType.MESSAGE) {
                log.warn("Received Bundle is not of type 'message'");
                return;
            }

            log.info("Parsing message bundle with {} entries", bundle.getEntry().size());

            // Capture the original message bundle ID for response linking
            String originalBundleId = bundle.hasId() ? bundle.getId() : null;

            // Extract resources from bundle
            MessageHeader messageHeader = null;
            Patient patient = null;
            CommunicationRequest communicationRequest = null;
            List<Practitioner> practitioners = new ArrayList<>();

            for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                Resource entryResource = entry.getResource();
                if (entryResource instanceof MessageHeader mh) {
                    messageHeader = mh;
                } else if (entryResource instanceof Patient p) {
                    patient = p;
                } else if (entryResource instanceof CommunicationRequest cr) {
                    communicationRequest = cr;
                } else if (entryResource instanceof Practitioner pr) {
                    practitioners.add(pr);
                }
            }

            // Only process if this is a CommunicationRequest message
            if (communicationRequest == null) {
                log.debug("Bundle does not contain CommunicationRequest, skipping");
                return;
            }

            // Create received request record
            ReceivedRequest receivedRequest = new ReceivedRequest();
            receivedRequest.setId(UUID.randomUUID().toString());
            receivedRequest.setReceivedAt(new Date());
            receivedRequest.setBundleJson(bundleJson);
            receivedRequest.setOriginalMessageBundleId(originalBundleId);

            // Extract MessageHeader info
            if (messageHeader != null) {
                receivedRequest.setEventCode(getEventCode(messageHeader));
                receivedRequest.setSourceName(getSourceName(messageHeader));

                if (messageHeader.hasSource()) {
                    MessageHeader.MessageSourceComponent source = messageHeader.getSource();
                    if (source.hasSoftware()) {
                        receivedRequest.setSourceSoftware(source.getSoftware());
                    }
                    if (source.hasVersion()) {
                        receivedRequest.setSourceVersion(source.getVersion());
                    }
                    if (source.hasContact() && source.getContact().hasValue()) {
                        receivedRequest.setSourceContact(source.getContact().getValue());
                    }
                }
            }

            // Extract Patient info
            if (patient != null) {
                receivedRequest.setPatientName(getPatientDisplayName(patient));
                receivedRequest.setPatientBirthDate(patient.hasBirthDate() ?
                        patient.getBirthDateElement().getValueAsString() : null);
                receivedRequest.setPatientId(patient.getId());
            }

            // Extract CommunicationRequest info
            receivedRequest.setRequestStatus(communicationRequest.hasStatus() ?
                    communicationRequest.getStatus().toCode() : null);
            receivedRequest.setRequestPriority(communicationRequest.hasPriority() ?
                    communicationRequest.getPriority().toCode() : null);
            receivedRequest.setRequestAuthoredOn(communicationRequest.getAuthoredOn());

            // Extract request description from payload
            if (communicationRequest.hasPayload() && !communicationRequest.getPayload().isEmpty()) {
                CommunicationRequest.CommunicationRequestPayloadComponent payload =
                        communicationRequest.getPayload().get(0);
                if (payload.hasContent() && payload.getContent() instanceof Attachment attachment) {
                    if (attachment.hasData()) {
                        String content = new String(attachment.getData(), StandardCharsets.UTF_8);
                        receivedRequest.setRequestDescription(content);
                    }
                }
            }

            // Extract requester info
            if (communicationRequest.hasRequester()) {
                String requesterRef = communicationRequest.getRequester().getReference();
                Practitioner requester = findPractitionerByRef(practitioners, requesterRef);
                if (requester != null) {
                    receivedRequest.setRequesterName(getPractitionerDisplayName(requester));
                } else if (communicationRequest.getRequester().hasDisplay()) {
                    receivedRequest.setRequesterName(communicationRequest.getRequester().getDisplay());
                }
            }

            // Extract recipient info
            if (communicationRequest.hasRecipient() && !communicationRequest.getRecipient().isEmpty()) {
                Reference recipientRef = communicationRequest.getRecipient().get(0);
                Practitioner recipient = findPractitionerByRef(practitioners, recipientRef.getReference());
                if (recipient != null) {
                    receivedRequest.setRecipientName(getPractitionerDisplayName(recipient));
                } else if (recipientRef.hasDisplay()) {
                    receivedRequest.setRecipientName(recipientRef.getDisplay());
                }
            }

            // Save to local FHIR server
            Bundle savedBundle = fhirService.saveBundle(bundle);
            if (savedBundle != null) {
                receivedRequest.setFhirBundleId(savedBundle.getIdElement().getIdPart());
                log.info("Saved request bundle to FHIR server with ID: {}",
                        savedBundle.getIdElement().getIdPart());
            }

            // Add to received requests list
            receivedRequests.add(receivedRequest);
            log.info("Processed CommunicationRequest from {} for patient {}",
                    receivedRequest.getRequesterName(), receivedRequest.getPatientName());

        } catch (Exception e) {
            log.error("Error parsing request bundle", e);
        }
    }

    public List<ReceivedRequest> getReceivedRequests() {
        synchronized (receivedRequests) {
            List<ReceivedRequest> sorted = new ArrayList<>(receivedRequests);
            sorted.sort((a, b) -> b.getReceivedAt().compareTo(a.getReceivedAt()));
            return sorted;
        }
    }

    public ReceivedRequest getRequest(String id) {
        synchronized (receivedRequests) {
            return receivedRequests.stream()
                    .filter(r -> r.getId().equals(id))
                    .findFirst()
                    .orElse(null);
        }
    }

    public int getRequestCount() {
        return receivedRequests.size();
    }

    private String getEventCode(MessageHeader messageHeader) {
        if (messageHeader.hasEvent() && messageHeader.getEvent() instanceof Coding coding) {
            return coding.getCode();
        }
        return "unknown";
    }

    private String getSourceName(MessageHeader messageHeader) {
        if (messageHeader.hasSource() && messageHeader.getSource().hasName()) {
            return messageHeader.getSource().getName();
        }
        return "Unknown Source";
    }

    private String getPatientDisplayName(Patient patient) {
        if (patient.hasName() && !patient.getName().isEmpty()) {
            HumanName name = patient.getName().get(0);
            StringBuilder display = new StringBuilder();
            if (name.hasGiven()) {
                for (StringType given : name.getGiven()) {
                    display.append(given.getValue()).append(" ");
                }
            }
            if (name.hasFamily()) {
                display.append(name.getFamily());
            }
            return display.toString().trim();
        }
        return "Unknown Patient";
    }

    private String getPractitionerDisplayName(Practitioner practitioner) {
        if (practitioner.hasName() && !practitioner.getName().isEmpty()) {
            HumanName name = practitioner.getName().get(0);
            StringBuilder display = new StringBuilder();
            if (name.hasPrefix()) {
                for (StringType prefix : name.getPrefix()) {
                    display.append(prefix.getValue()).append(" ");
                }
            }
            if (name.hasGiven()) {
                for (StringType given : name.getGiven()) {
                    display.append(given.getValue()).append(" ");
                }
            }
            if (name.hasFamily()) {
                display.append(name.getFamily());
            }
            return display.toString().trim();
        }
        return "Unknown Practitioner";
    }

    private Practitioner findPractitionerByRef(List<Practitioner> practitioners, String reference) {
        if (reference == null) return null;
        String id = reference.startsWith("urn:uuid:") ? reference.substring(9) : reference;
        return practitioners.stream()
                .filter(p -> p.getId().equals(id))
                .findFirst()
                .orElse(null);
    }
}
