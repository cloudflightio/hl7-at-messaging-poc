package at.hl7.fhir.poc.his.controller;

import at.hl7.fhir.poc.his.model.ReceivedRequest;
import at.hl7.fhir.poc.his.service.FhirService;
import at.hl7.fhir.poc.his.service.MatrixService;
import at.hl7.fhir.poc.his.service.RequestBundleParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Patient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@Slf4j
public class HomeController {

    private final FhirService fhirService;
    private final MatrixService matrixService;
    private final RequestBundleParser requestBundleParser;

    @GetMapping("/")
    public String home(Model model) {
        List<Patient> patients = fhirService.getAllPatients();
        List<Bundle> sentMessages = fhirService.getMessageBundles();

        model.addAttribute("patients", patients);
        model.addAttribute("sentMessages", sentMessages);
        model.addAttribute("matrixConnected", matrixService.isConnected());
        model.addAttribute("requestCount", requestBundleParser.getRequestCount());

        return "index";
    }

    @GetMapping("/status")
    public String status(Model model) {
        model.addAttribute("matrixConnected", matrixService.isConnected());
        model.addAttribute("matrixRoomId", matrixService.getRoomId());
        return "status";
    }

    @GetMapping("/requests")
    public String receivedRequests(Model model) {
        List<ReceivedRequest> requests = requestBundleParser.getReceivedRequests();

        model.addAttribute("requests", requests);
        model.addAttribute("requestCount", requests.size());
        model.addAttribute("matrixConnected", matrixService.isConnected());

        return "received-requests";
    }

    @GetMapping("/api/requests/count")
    @ResponseBody
    public Map<String, Object> getRequestCount() {
        Map<String, Object> response = new HashMap<>();
        response.put("count", requestBundleParser.getRequestCount());
        response.put("connected", matrixService.isConnected());
        return response;
    }
}
