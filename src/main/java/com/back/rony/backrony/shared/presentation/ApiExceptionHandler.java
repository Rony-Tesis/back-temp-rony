package com.back.rony.backrony.shared.presentation;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable error envelope; internal stack traces and connection credentials stay private. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<Map<String, String>> invalid(Exception e) {
        return ResponseEntity.unprocessableContent()
                .body(Map.of("detail", "Cuerpo JSON inválido o campos no admitidos."));
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        return ResponseEntity.status(409)
                .body(
                        Map.of(
                                "detail",
                                e.getMessage() == null
                                        ? "Operación no admisible."
                                        : e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception e) {
        LOG.error("Request failed with {}", e.getClass().getSimpleName());
        return ResponseEntity.internalServerError()
                .body(Map.of("detail", "Error interno; revisa el servicio."));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<Map<String, String>> notFound(Exception e) {
        return ResponseEntity.status(404).body(Map.of("detail", "Ruta no encontrada."));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, String>> methodNotAllowed(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405)
                .allow(
                        e.getSupportedHttpMethods()
                                .toArray(org.springframework.http.HttpMethod[]::new))
                .body(Map.of("detail", "Método no permitido."));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, String>> unsupportedMedia(Exception e) {
        return ResponseEntity.status(415).body(Map.of("detail", "Se requiere application/json."));
    }
}
