package at.hl7.fhir.poc.his.service;

import at.hl7.fhir.poc.his.model.ReceivedRequest;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Date;
import java.util.UUID;

/**
 * Builds ATMessagingBundle according to the AT Messaging Implementation Guide.
 * Creates FHIR R5 message bundles with MessageHeader, Endpoints, and payloads.
 */
@Service
@Slf4j
public class BundleBuilder {

    @Value("${his.endpoint.name:Hospital Information System}")
    private String hisEndpointName;

    @Value("${his.endpoint.address:matrix:@his_user:matrix.local}")
    private String hisEndpointAddress;

    @Value("${gp.endpoint.name:General Practitioner}")
    private String gpEndpointName;

    @Value("${gp.endpoint.address:matrix:@gp_user:matrix.local}")
    private String gpEndpointAddress;

    /**
     * Creates an ATMessagingBundle for sending a document to the GP.
     * Follows the AT Messaging IG specification with required MessageHeader fields.
     */
    public Bundle createDocumentMessageBundle(Patient patient, MultipartFile pdfFile, String title) throws IOException {
        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.MESSAGE);
        bundle.setTimestamp(new Date());

        // Add meta profile for ATMessagingBundle
        bundle.getMeta().addProfile("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-bundle");

        // Create source endpoint (HIS)
        Endpoint sourceEndpoint = createEndpoint(
                hisEndpointName,
                hisEndpointAddress
        );

        // Create destination endpoint (GP)
        Endpoint destinationEndpoint = createEndpoint(
                gpEndpointName,
                gpEndpointAddress
        );

        // Create receiver Practitioner (Dr. Huber)
        Practitioner receiver = createReceiverPractitioner();

        Organization senderOrganization = createSenderOrganization();
        Practitioner senderPractitioner = createSenderPractitioner();

        PractitionerRole senderPractitionerRole = createSenderPractitionerRole(senderPractitioner, senderOrganization);

        // Get PDF content
        byte[] pdfContent = pdfFile.getBytes();
        String filename = pdfFile.getOriginalFilename();

        setMessageIdToPatient(patient);

        // Create the nursing document
        DocumentReference document = createNursingDocument(patient, title, pdfContent, filename, senderPractitionerRole);

        // Create MessageHeader with receiver reference
        MessageHeader messageHeader = createMessageHeader(
                sourceEndpoint,
                destinationEndpoint,
                receiver,
                senderPractitionerRole
        );

        Encounter encounter = createEncounter();

        // Add entries to bundle (MessageHeader must be first)
        addEntry(bundle, messageHeader);
        addEntry(bundle, sourceEndpoint);
        addEntry(bundle, destinationEndpoint);
        addEntry(bundle, receiver);
        addEntry(bundle, patient);
        addEntry(bundle, document);
        addEntry(bundle, senderOrganization);
        addEntry(bundle, senderPractitionerRole);
        addEntry(bundle, senderPractitioner);
        addEntry(bundle, encounter);

        // Set MessageHeader focus to the document
        messageHeader.addFocus(new Reference("urn:uuid:" + document.getId()).setType("DocumentReference"));
        messageHeader.addFocus(new Reference("urn:uuid:" + patient.getId()).setType("Patient"));
        messageHeader.addFocus(new Reference("urn:uuid:" + encounter.getId()).setType("Encounter"));

        log.info("Created ATMessagingBundle with {} entries", bundle.getEntry().size());
        return bundle;
    }

    /**
     * Creates an ATMessagingBundle for sending a document as a response to a CommunicationRequest.
     * Sets the MessageHeader.response to reference the original request bundle.
     */
    public Bundle createDocumentResponseBundle(ReceivedRequest originalRequest,
                                               MultipartFile pdfFile,
                                               String title,
                                               String originalBundleId) throws IOException {
        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.MESSAGE);
        bundle.setTimestamp(new Date());

        // Add meta profile for ATMessagingBundle
        bundle.getMeta().addProfile("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-bundle");

        // Create endpoints
        Endpoint sourceEndpoint = createEndpoint(hisEndpointName, hisEndpointAddress);
        Endpoint destinationEndpoint = createEndpoint(gpEndpointName, gpEndpointAddress);

        // Create practitioners
        Practitioner receiver = createReceiverPractitioner();
        Organization senderOrganization = createSenderOrganization();
        Practitioner senderPractitioner = createSenderPractitioner();
        PractitionerRole senderPractitionerRole = createSenderPractitionerRole(senderPractitioner, senderOrganization);

        // Create patient from original request data
        Patient patient = createPatientFromRequest(originalRequest);

        // Get PDF content
        byte[] pdfContent = pdfFile.getBytes();
        String filename = pdfFile.getOriginalFilename();

        // Create the document
        DocumentReference document = createNursingDocument(patient, title, pdfContent, filename, senderPractitionerRole);

        // Create MessageHeader with response reference
        MessageHeader messageHeader = createMessageHeaderWithResponse(
                sourceEndpoint, destinationEndpoint, receiver, senderPractitionerRole, originalBundleId);

        Encounter encounter = createEncounter();

        // Add entries to bundle (MessageHeader must be first)
        addEntry(bundle, messageHeader);
        addEntry(bundle, sourceEndpoint);
        addEntry(bundle, destinationEndpoint);
        addEntry(bundle, receiver);
        addEntry(bundle, patient);
        addEntry(bundle, document);
        addEntry(bundle, senderOrganization);
        addEntry(bundle, senderPractitionerRole);
        addEntry(bundle, senderPractitioner);
        addEntry(bundle, encounter);

        // Set MessageHeader focus
        messageHeader.addFocus(new Reference("urn:uuid:" + document.getId()).setType("DocumentReference"));
        messageHeader.addFocus(new Reference("urn:uuid:" + patient.getId()).setType("Patient"));
        messageHeader.addFocus(new Reference("urn:uuid:" + encounter.getId()).setType("Encounter"));

        log.info("Created ATMessagingBundle document response with {} entries", bundle.getEntry().size());
        return bundle;
    }

    private Patient createPatientFromRequest(ReceivedRequest request) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID().toString());
        patient.getMeta().addProfile("http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-patient");

        if (request.getPatientName() != null) {
            HumanName name = patient.addName();
            String[] nameParts = request.getPatientName().split(" ");
            if (nameParts.length >= 2) {
                name.setFamily(nameParts[nameParts.length - 1]);
                for (int i = 0; i < nameParts.length - 1; i++) {
                    name.addGiven(nameParts[i]);
                }
            } else {
                name.setFamily(request.getPatientName());
            }
            name.setUse(HumanName.NameUse.OFFICIAL);
        }

        if (request.getPatientBirthDate() != null) {
            patient.setBirthDateElement(new DateType(request.getPatientBirthDate()));
        }

        return patient;
    }

    private MessageHeader createMessageHeaderWithResponse(Endpoint source, Endpoint destination,
                                                          Practitioner receiver, PractitionerRole sender,
                                                          String originalBundleId) {
        MessageHeader messageHeader = new MessageHeader();
        messageHeader.setId(UUID.randomUUID().toString());

        messageHeader.getMeta().addProfile("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-message-header");

        // Event type - document transfer
        messageHeader.setEvent(new Coding()
                .setSystem("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/CodeSystem/at-messaging-event-type")
                .setCode("document")
                .setDisplay("Document Transfer"));

        messageHeader.setDefinition("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/MessageDefinition/at-messaging-document-message");

        // Response - reference to the original request bundle
        if (originalBundleId != null && !originalBundleId.isEmpty()) {
            MessageHeader.MessageHeaderResponseComponent response = messageHeader.getResponse();
            response.setIdentifier(new Identifier().setValue(originalBundleId));
            response.setCode(MessageHeader.ResponseType.OK);
        }

        // Source
        MessageHeader.MessageSourceComponent sourceComponent = messageHeader.getSource();
        sourceComponent.setEndpoint(new Reference("urn:uuid:" + source.getId()));
        sourceComponent.setName(hisEndpointName);
        sourceComponent.setSoftware("at.hl7.fhir.poc.his");
        sourceComponent.setVersion("0.1.0");
        sourceComponent.setContact(new ContactPoint()
                .setSystem(ContactPoint.ContactPointSystem.EMAIL)
                .setValue("dummy.his.support@example.com"));

        // Destination
        MessageHeader.MessageDestinationComponent destComponent = messageHeader.addDestination();
        destComponent.setEndpoint(new Reference("urn:uuid:" + destination.getId()));
        destComponent.setName(gpEndpointName);
        destComponent.setReceiver(new Reference("urn:uuid:" + receiver.getId())
                .setDisplay("Dr. Johann Huber"));

        messageHeader.setSender(new Reference("urn:uuid:" + sender.getId()));
        messageHeader.setAuthor(new Reference("urn:uuid:" + sender.getId()));

        return messageHeader;
    }

    /**
     * Creates a Practitioner resource for the receiver (Dr. Mayer).
     */
    private Practitioner createSenderPractitioner() {
        Practitioner practitioner = new Practitioner();
        practitioner.setId(UUID.randomUUID().toString());
        practitioner.getMeta().addProfile("http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-practitioner");

        // Name
        HumanName name = practitioner.addName();
        name.setFamily("Mayer");
        name.addPrefix("Dr.");
        name.addGiven("Selina");

        // Identifier (example GDA number)
        practitioner.addIdentifier()
                .setSystem("urn:oid:1.2.40.0.10.1.4.3.2")
                .setValue("GP-54321");

        return practitioner;
    }

    private Organization createSenderOrganization() {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID().toString());
        organization.getMeta().addProfile("http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-organization");
        organization.addIdentifier()
                .setSystem("urn:ietf:rfc:3986")
                .setValue("1.2.40.0.34.66.77.99999");
        organization.addIdentifier()
                .setSystem("urn:oid:1.2.40.0.10.1.4.3.2")
                .setValue("999111");
        organization.addIdentifier()
                .setSystem("urn:oid:1.2.40.0.34.4.10")
                .setValue("K999");
        organization.addType().addCoding()
                .setSystem("https://termgit.elga.gv.at/CodeSystem/elga-gtelvogdarollen")
                .setCode("300")
                .setDisplay("Allgemeine Krankenanstalt");
        return organization;
    }

    private PractitionerRole createSenderPractitionerRole(Practitioner senderPractitioner, Organization senderOrganization) {
        PractitionerRole practitionerRole = new PractitionerRole();
        practitionerRole.setId(UUID.randomUUID().toString());
        practitionerRole.getMeta().addProfile("http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-practitionerRole");
        practitionerRole.setPractitioner(new Reference(senderPractitioner.getId()));
        practitionerRole.setOrganization(new Reference(senderOrganization.getId()));
        practitionerRole.addCode().addCoding().setSystem("https://termgit.elga.gv.at/CodeSystem/elga-gtelvogdarollen").setCode("1000").setDisplay("Ärztin/Arzt");
        return practitionerRole;
    }

    private Encounter createEncounter() {
        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID().toString());
        encounter.setStatus(Enumerations.EncounterStatus.INPROGRESS);
        // TODO - the scope and profiling of the Encounter is still WIP
        return encounter;
    }

    private void setMessageIdToPatient(Patient patient) {
        patient.addIdentifier()
                .setSystem("http://mydummyhis.example.com/Identifiers/Patient")
                .setValue(patient.getId());
        UUID patientMessageId = UUID.randomUUID();
        patient.setId(patientMessageId.toString());
    }

    /**
     * Creates a Practitioner resource for the receiver (Dr. Huber).
     */
    private Practitioner createReceiverPractitioner() {
        Practitioner practitioner = new Practitioner();
        practitioner.setId(UUID.randomUUID().toString());
        practitioner.getMeta().addProfile("http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-practitioner");

        // Name
        HumanName name = practitioner.addName();
        name.setFamily("Huber");
        name.addPrefix("Dr.");
        name.addGiven("Johann");

        // Identifier (example GDA number)
        practitioner.addIdentifier()
                .setSystem("urn:oid:1.2.40.0.10.1.4.3.2")
                .setValue("GP-12345");

        return practitioner;
    }

    private Endpoint createEndpoint(String name, String address) {
        Endpoint endpoint = new Endpoint();
        endpoint.setId(UUID.randomUUID().toString());
        endpoint.getMeta().addProfile("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-endpoint");
        endpoint.setStatus(Endpoint.EndpointStatus.ACTIVE);
        endpoint.setName(name);
        endpoint.setAddress(address);

        // Set connection type for Matrix
        endpoint.addConnectionType(new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/CodeSystem/at-messaging-endpoint-type")
                        .setCode("matrix")
                        .setDisplay("The message is transported over the Matrix protocol.")));

        return endpoint;
    }

    private MessageHeader createMessageHeader(Endpoint source, Endpoint destination,
                                              Practitioner receiver, PractitionerRole sender) {
        MessageHeader messageHeader = new MessageHeader();
        messageHeader.setId(UUID.randomUUID().toString());

        // Meta profile for ATMessagingMessageHeader
        messageHeader.getMeta().addProfile("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-message-header");

        // Event type - document transfer (per ATMessaging event type CodeSystem)
        messageHeader.setEvent(new Coding()
                .setSystem("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/CodeSystem/at-messaging-event-type")
                .setCode("document")
                .setDisplay("Document Transfer"));

        // Definition - canonical URL to ATMessagingDocumentMessageDefinition
        messageHeader.setDefinition("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/MessageDefinition/at-messaging-document-message");

        // Source with required ATMessagingMessageHeader fields
        MessageHeader.MessageSourceComponent sourceComponent = messageHeader.getSource();
        sourceComponent.setEndpoint(new Reference("urn:uuid:" + source.getId()));
        sourceComponent.setName(hisEndpointName);
        sourceComponent.setSoftware("at.hl7.fhir.poc.his");
        sourceComponent.setVersion("0.1.0");
        sourceComponent.setContact(new ContactPoint().setSystem(ContactPoint.ContactPointSystem.EMAIL).setValue("dummy.his.support@example.com"));

        // Destination with endpoint reference and receiver
        MessageHeader.MessageDestinationComponent destComponent = messageHeader.addDestination();
        destComponent.setEndpoint(new Reference("urn:uuid:" + destination.getId()));
        destComponent.setName(gpEndpointName);
        destComponent.setReceiver(new Reference("urn:uuid:" + receiver.getId())
                .setDisplay("Dr. Johann Huber"));

        // We assume that the author has sent the message themselves, therefore setting sender and author to the same value
        messageHeader.setSender(new Reference("urn:uuid:" + sender.getId()));
        messageHeader.setAuthor(new Reference("urn:uuid:" + sender.getId()));

        return messageHeader;
    }

    private void addEntry(Bundle bundle, Resource resource) {
        Bundle.BundleEntryComponent entry = bundle.addEntry();
        entry.setFullUrl("urn:uuid:" + resource.getId());
        entry.setResource(resource);
    }

    /**
     * Creates a DocumentReference for a nursing document with PDF content.
     * Uses LOINC code 34746-8 "Nurse Note" and IHE MHD SimplifiedPublish profile.
     */
    private DocumentReference createNursingDocument(Patient patient, String title,
                                                    byte[] pdfContent, String filename, PractitionerRole author) {
        DocumentReference docRef = new DocumentReference();
        docRef.setId(UUID.randomUUID().toString());
        docRef.setStatus(DocumentReference.DocumentReferenceStatus.CURRENT);

        // Meta profile for IHE MHD
        docRef.getMeta().addProfile("https://profiles.ihe.net/ITI/MHD/StructureDefinition/IHE.MHD.SimplifiedPublish.DocumentReference");

        // Document type - Nurse Note (LOINC 34746-8)
        docRef.setType(new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem("http://loinc.org")
                        .setCode("34746-8")
                        .setDisplay("Nurse Note")));

        // Category
        docRef.addCategory(new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem("http://loinc.org")
                        .setCode("11543-6")
                        .setDisplay("Nursery records")));

        // Subject reference
        docRef.setSubject(new Reference("urn:uuid:" + patient.getId())
                .setDisplay(getPatientDisplayName(patient)));

        // Date
        docRef.setDate(new Date());

        // Description
        docRef.setDescription(title);

        docRef.addAuthor(new Reference("urn:uuid:" + author.getId()));

        // Content - PDF attachment
        DocumentReference.DocumentReferenceContentComponent contentComponent = docRef.addContent();
        Attachment attachment = new Attachment();
        attachment.setContentType("application/pdf");
        attachment.setLanguage("de");
        attachment.setData(pdfContent);
        attachment.setTitle(filename != null ? filename : title + ".pdf");
        attachment.setCreation(new Date());
        contentComponent.setAttachment(attachment);

        return docRef;
    }

    private String getPatientDisplayName(Patient patient) {
        if (patient.hasName() && !patient.getName().isEmpty()) {
            HumanName name = patient.getName().getFirst();
            StringBuilder display = new StringBuilder();
            if (name.hasGiven()) {
                display.append(name.getGiven().getFirst().getValue());
            }
            if (name.hasFamily()) {
                if (!display.isEmpty()) display.append(" ");
                display.append(name.getFamily());
            }
            return display.toString();
        }
        return "Unknown Patient";
    }
}
