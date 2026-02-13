package at.hl7.fhir.poc.his.controller;

import at.hl7.fhir.poc.his.service.FhirService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.Patient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/patients")
@RequiredArgsConstructor
@Slf4j
public class PatientController {

    private final FhirService fhirService;

    @GetMapping
    public String listPatients(Model model) {
        List<Patient> patients = fhirService.getAllPatients();
        model.addAttribute("patients", patients);
        return "patients";
    }

    @GetMapping("/{id}")
    public String viewPatient(@PathVariable String id, Model model) {
        Patient patient = fhirService.getPatient(id);
        if (patient == null) {
            return "redirect:/patients";
        }
        model.addAttribute("patient", patient);
        return "patient-detail";
    }
}
