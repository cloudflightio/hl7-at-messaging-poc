package at.hl7.fhir.poc.gp.controller;

import at.hl7.fhir.poc.gp.model.ReceivedMessage;
import at.hl7.fhir.poc.gp.model.SentRequest;
import at.hl7.fhir.poc.gp.service.BundleParser;
import at.hl7.fhir.poc.gp.service.FhirService;
import at.hl7.fhir.poc.gp.service.MatrixService;
import at.hl7.fhir.poc.gp.service.SentRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.Bundle;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@Slf4j
public class InboxController {

    private final BundleParser bundleParser;
    private final FhirService fhirService;
    private final MatrixService matrixService;
    private final SentRequestService sentRequestService;

    @GetMapping("/")
    public String inbox(Model model) {
        List<ReceivedMessage> messages = bundleParser.getReceivedMessages();
        model.addAttribute("messages", messages);
        model.addAttribute("messageCount", messages.size());
        model.addAttribute("matrixConnected", matrixService.isConnected());

        return "index";
    }

    @GetMapping("/messages/{id}")
    public String viewMessage(@PathVariable String id, Model model) {
        ReceivedMessage message = bundleParser.getMessage(id);
        if (message == null) {
            return "redirect:/";
        }

        model.addAttribute("message", message);

        // Get the full bundle from FHIR server if available
        if (message.getFhirBundleId() != null) {
            Bundle bundle = fhirService.getBundle(message.getFhirBundleId());
            if (bundle != null) {
                model.addAttribute("bundleJson", fhirService.serializeResource(bundle));
            }
        }

        return "message-detail";
    }

    @GetMapping("/sent-requests/{id}")
    public String viewSentRequest(@PathVariable String id, Model model) {
        SentRequest request = sentRequestService.getRequestById(id);
        if (request == null) {
            return "redirect:/";
        }

        model.addAttribute("request", request);
        return "sent-request-detail";
    }

    /**
     * View a sent request by its original bundle ID.
     * This is used when clicking on the "Response To" link in a received message.
     */
    @GetMapping("/sent-requests/by-bundle/{bundleId}")
    public String viewSentRequestByBundleId(@PathVariable String bundleId, Model model) {
        SentRequest request = sentRequestService.getRequestByBundleId(bundleId);
        if (request == null) {
            model.addAttribute("error", "Original request not found for bundle ID: " + bundleId);
            return "redirect:/";
        }

        model.addAttribute("request", request);
        return "sent-request-detail";
    }

    @GetMapping("/api/messages/count")
    @ResponseBody
    public Map<String, Object> getMessageCount() {
        Map<String, Object> response = new HashMap<>();
        response.put("count", bundleParser.getMessageCount());
        response.put("connected", matrixService.isConnected());
        return response;
    }

    @GetMapping("/api/messages")
    @ResponseBody
    public List<ReceivedMessage> getMessages() {
        return bundleParser.getReceivedMessages();
    }

    @GetMapping("/messages/{id}/download")
    public ResponseEntity<byte[]> downloadDocument(@PathVariable String id) {
        ReceivedMessage message = bundleParser.getMessage(id);
        if (message == null) {
            return ResponseEntity.notFound().build();
        }

        String base64Data = message.getDocumentBase64Data();
        if (base64Data == null || base64Data.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("No downloadable document available".getBytes());
        }

        try {
            byte[] pdfContent = Base64.getDecoder().decode(base64Data);

            String filename = message.getDocumentFilename();
            if (filename == null || filename.isEmpty()) {
                filename = message.getDocumentTitle() != null ?
                        message.getDocumentTitle() + ".pdf" : "document.pdf";
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(pdfContent.length);

            return new ResponseEntity<>(pdfContent, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Error decoding PDF content", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing document".getBytes());
        }
    }
}
