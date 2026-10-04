package com.back.rony.backrony.shared.presentation;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InformationController {
    @GetMapping("/")
    @Operation(summary = "Consultar la información de la API")
    public Map<String, String> information() {
        return Map.of(
                "service",
                "Rony Spring API",
                "frontend",
                "http://127.0.0.1:4200/monitoring",
                "openapi",
                "/api/v1/openapi.json");
    }
}
