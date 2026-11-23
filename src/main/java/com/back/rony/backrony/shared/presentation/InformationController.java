package com.back.rony.backrony.shared.presentation;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "rony.web-portal",
        havingValue = "false",
        matchIfMissing = true)
public class InformationController {
    private final String frontendUrl;

    public InformationController(
            @Value("${rony.frontend-url:http://127.0.0.1:4200}") String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }

    @GetMapping("/")
    @Operation(summary = "Consultar la información de la API")
    public Map<String, String> information() {
        return Map.of(
                "service",
                "Rony Spring API",
                "frontend",
                frontendUrl.replaceAll("/+$", "") + "/monitoring",
                "openapi",
                "/api/v1/openapi.json");
    }
}
