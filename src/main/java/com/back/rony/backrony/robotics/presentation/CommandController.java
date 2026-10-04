package com.back.rony.backrony.robotics.presentation;

import com.back.rony.backrony.monitoring.application.MonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerToken")
@SecurityRequirement(name = "sessionCookie")
public class CommandController {
    private final MonitorService monitor;

    public CommandController(MonitorService monitor) {
        this.monitor = monitor;
    }

    public record PhysicalCommand(@NotNull @Pattern(regexp = "arm|cancel|reset") String action) {}

    public record VirtualCommand(@NotNull @Pattern(regexp = "pause|resume|cycle") String action) {}

    @PostMapping(value = "/robot/commands", consumes = "application/json")
    @Operation(
            summary = "Solicitar activar, cancelar o resetear el JetMax",
            description =
                    "200 confirma aceptación, no fin del movimiento. Reset vuelve a HOME y abre la pinza; cancel espera el tramo en curso.")
    @Parameter(
            name = "X-Rony-Control",
            in = ParameterIn.HEADER,
            required = true,
            description = "Valor obligatorio: 1")
    @ApiResponses({
        @ApiResponse(responseCode = "403", description = "Cabecera u origen inválido"),
        @ApiResponse(responseCode = "409", description = "Modo o estado incompatible"),
        @ApiResponse(responseCode = "422", description = "JSON inválido")
    })
    public Map<String, Object> physical(@Valid @RequestBody PhysicalCommand command) {
        monitor.physicalCommand(command.action());
        return monitor.status();
    }

    @PostMapping(value = "/simulation/commands", consumes = "application/json")
    @Operation(summary = "Pausar, reanudar o ejecutar un ciclo virtual")
    @Parameter(
            name = "X-Rony-Control",
            in = ParameterIn.HEADER,
            required = true,
            description = "Valor obligatorio: 1")
    public Map<String, Object> virtual(@Valid @RequestBody VirtualCommand command) {
        monitor.virtualCommand(command.action());
        return monitor.status();
    }
}
