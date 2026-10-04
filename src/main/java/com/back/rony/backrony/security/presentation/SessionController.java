package com.back.rony.backrony.security.presentation;

import com.back.rony.backrony.security.application.AccessPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    private final AccessPolicy policy;

    public SessionController(AccessPolicy policy) {
        this.policy = policy;
    }

    public record Login(@NotNull @Size(min = 24, max = 256) String token) {}

    @PostMapping(value = "/api/v1/session", consumes = "application/json")
    @Operation(summary = "Validar el token configurado y crear una cookie HttpOnly por una hora")
    @Parameter(
            name = "X-Rony-Control",
            in = ParameterIn.HEADER,
            required = true,
            description = "Valor obligatorio: 1")
    public ResponseEntity<?> login(@Valid @RequestBody Login login, HttpServletRequest request) {
        if (!policy.matches(login.token()))
            return ResponseEntity.status(401).body(Map.of("detail", "Token inválido."));
        ResponseCookie cookie =
                ResponseCookie.from("rony_session", login.token())
                        .httpOnly(true)
                        .sameSite("Strict")
                        .secure(request.isSecure())
                        .path("/api")
                        .maxAge(3600)
                        .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("authenticated", true));
    }
}
