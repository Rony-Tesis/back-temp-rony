package com.back.rony.backrony.shared.infrastructure;

import com.back.rony.backrony.history.application.port.HistoryPort;
import com.back.rony.backrony.monitoring.application.MonitorService;
import com.back.rony.backrony.monitoring.application.port.VisionPort;
import com.back.rony.backrony.monitoring.infrastructure.MiraVisionAdapter;
import com.back.rony.backrony.monitoring.infrastructure.SimulatedVisionAdapter;
import com.back.rony.backrony.robotics.infrastructure.JetMaxRobotAdapter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class ApplicationConfiguration {
    @Bean(destroyMethod = "close")
    MonitorService monitor(RonyProperties properties, HistoryPort history, ObjectMapper mapper) {
        VisionPort source =
                "live".equals(properties.sourceMode())
                        ? new MiraVisionAdapter(properties, mapper)
                        : new SimulatedVisionAdapter(properties.framesPerSecond());
        return new MonitorService(
                source,
                properties.sourceMode(),
                history,
                properties.physicalMotion()
                        ? () -> new JetMaxRobotAdapter(properties, mapper)
                        : null);
    }

    @Bean
    WorkerStartup workerStartup(MonitorService monitor, RonyProperties properties) {
        return new WorkerStartup(monitor, properties);
    }

    record WorkerStartup(MonitorService monitor, RonyProperties properties) {
        @EventListener(ApplicationReadyEvent.class)
        public void start() {
            if (properties.workersEnabled()) monitor.start();
        }
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Rony cámara y JetMax")
                                .version("1.0.0")
                                .description(
                                        "API Spring Boot compatible con Angular. SSE/MJPEG y control físico asíncrono. El token es opcional en configuración local."))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerToken",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer"))
                                .addSecuritySchemes(
                                        "sessionCookie",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.COOKIE)
                                                .name("rony_session")));
    }
}
