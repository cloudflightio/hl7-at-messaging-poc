package at.hl7.fhir.poc.his.controller;

import at.hl7.fhir.poc.his.model.ReceivedRequest;
import at.hl7.fhir.poc.his.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.Bundle;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for handling replies to CommunicationRequest messages.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ReplyController {

    private final RequestBundleParser requestBundleParser;
    private final CommunicationBundleBuilder communicationBundleBuilder;
    private final BundleBuilder bundleBuilder;
    private final FhirService fhirService;
    private final MatrixService matrixService;

    @GetMapping("/requests/{id}/reply")
    public String showReplyForm(@PathVariable String id, Model model) {
        ReceivedRequest request = requestBundleParser.getRequest(id);
        if (request == null) {
            return "redirect:/requests";
        }

        model.addAttribute("request", request);
        model.addAttribute("matrixConnected", matrixService.isConnected());

        return "reply-request";
    }

    @PostMapping("/requests/{id}/reply/text")
    public String sendTextReply(@PathVariable String id,
                                @RequestParam String messageText,
                                RedirectAttributes redirectAttributes) {
        ReceivedRequest request = requestBundleParser.getRequest(id);
        if (request == null) {
            redirectAttributes.addFlashAttribute("error", "Request not found");
            return "redirect:/requests";
        }

        if (messageText == null || messageText.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Please enter a message");
            return "redirect:/requests/" + id + "/reply";
        }

        try {
            // Build Communication response bundle
            Bundle bundle = communicationBundleBuilder.createCommunicationResponseBundle(
                    request, messageText, request.getOriginalMessageBundleId());

            // Save bundle locally
            fhirService.saveBundle(bundle);

            // Serialize and send via Matrix
            String bundleJson = fhirService.serializeResource(bundle);
            boolean sent = matrixService.sendFhirMessage(bundleJson);

            if (sent) {
                log.info("Successfully sent text reply for request: {}", id);
                redirectAttributes.addFlashAttribute("success",
                        "Text reply sent successfully to " + request.getRequesterName());
            } else {
                log.error("Failed to send text reply via Matrix");
                redirectAttributes.addFlashAttribute("error",
                        "Failed to send reply. Matrix connection issue.");
            }

        } catch (Exception e) {
            log.error("Error sending text reply", e);
            redirectAttributes.addFlashAttribute("error",
                    "Error sending reply: " + e.getMessage());
        }

        return "redirect:/requests";
    }

    @PostMapping("/requests/{id}/reply/document")
    public String sendDocumentReply(@PathVariable String id,
                                    @RequestParam String title,
                                    @RequestParam("file") MultipartFile file,
                                    RedirectAttributes redirectAttributes) {
        ReceivedRequest request = requestBundleParser.getRequest(id);
        if (request == null) {
            redirectAttributes.addFlashAttribute("error", "Request not found");
            return "redirect:/requests";
        }

        if (title == null || title.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Please enter a document title");
            return "redirect:/requests/" + id + "/reply";
        }

        if (file == null || file.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Please select a PDF file");
            return "redirect:/requests/" + id + "/reply";
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            redirectAttributes.addFlashAttribute("error", "Only PDF files are allowed");
            return "redirect:/requests/" + id + "/reply";
        }

        try {
            // Build document response bundle
            Bundle bundle = bundleBuilder.createDocumentResponseBundle(
                    request, file, title, request.getOriginalMessageBundleId());

            // Save bundle locally
            fhirService.saveBundle(bundle);

            // Serialize and send via Matrix
            String bundleJson = fhirService.serializeResource(bundle);
            boolean sent = matrixService.sendFhirMessage(bundleJson);

            if (sent) {
                log.info("Successfully sent document reply for request: {}", id);
                redirectAttributes.addFlashAttribute("success",
                        "Document '" + title + "' sent successfully to " + request.getRequesterName());
            } else {
                log.error("Failed to send document reply via Matrix");
                redirectAttributes.addFlashAttribute("error",
                        "Failed to send document. Matrix connection issue.");
            }

        } catch (Exception e) {
            log.error("Error sending document reply", e);
            redirectAttributes.addFlashAttribute("error",
                    "Error sending document: " + e.getMessage());
        }

        return "redirect:/requests";
    }
}
