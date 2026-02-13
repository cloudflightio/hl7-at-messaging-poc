package at.hl7.fhir.poc.his.controller;

import at.hl7.fhir.poc.his.service.BundleBuilder;
import at.hl7.fhir.poc.his.service.FhirService;
import at.hl7.fhir.poc.his.service.MatrixService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Patient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/documents")
@RequiredArgsConstructor
@Slf4j
public class DocumentController {

    private final FhirService fhirService;
    private final BundleBuilder bundleBuilder;
    private final MatrixService matrixService;

    @GetMapping("/create")
    public String showCreateForm(@RequestParam String patientId, Model model) {
        Patient patient = fhirService.getPatient(patientId);
        if (patient == null) {
            return "redirect:/patients";
        }
        model.addAttribute("patient", patient);
        model.addAttribute("patientId", patientId);
        return "send-document";
    }

    @PostMapping("/send")
    public String sendDocument(
            @RequestParam String patientId,
            @RequestParam String title,
            @RequestParam("pdfFile") MultipartFile pdfFile,
            RedirectAttributes redirectAttributes) {

        try {
            // Validate PDF file
            if (pdfFile.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Please select a PDF file to upload");
                return "redirect:/documents/create?patientId=" + patientId;
            }

            String contentType = pdfFile.getContentType();
            if (contentType == null || !contentType.equals("application/pdf")) {
                redirectAttributes.addFlashAttribute("error", "Only PDF files are allowed");
                return "redirect:/documents/create?patientId=" + patientId;
            }

            // Get the patient
            Patient patient = fhirService.getPatient(patientId);
            if (patient == null) {
                redirectAttributes.addFlashAttribute("error", "Patient not found");
                return "redirect:/patients";
            }

            // Create the FHIR messaging bundle
            Bundle messageBundle = bundleBuilder.createDocumentMessageBundle(patient, pdfFile, title);

            // Save the bundle to local FHIR server
            fhirService.saveBundle(messageBundle);

            // Serialize and send via Matrix
            String bundleJson = fhirService.serializeResource(messageBundle);
            boolean sent = matrixService.sendFhirMessage(bundleJson);

            if (sent) {
                redirectAttributes.addFlashAttribute("success",
                        "Nursing document '" + title + "' sent successfully to GP");
                log.info("Nursing document sent successfully: {} for patient {}", title, patientId);
            } else {
                redirectAttributes.addFlashAttribute("error",
                        "Failed to send document via Matrix. Please try again.");
                log.error("Failed to send document via Matrix");
            }

        } catch (Exception e) {
            log.error("Error sending document", e);
            redirectAttributes.addFlashAttribute("error", "Error sending document: " + e.getMessage());
        }

        return "redirect:/";
    }
}
