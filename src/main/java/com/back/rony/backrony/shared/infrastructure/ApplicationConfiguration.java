package com.back.rony.backrony.shared.infrastructure;

import com.back.rony.backrony.history.application.port.HistoryPort;
import com.back.rony.backrony.monitoring.application.CloudDeviceRelay;
import com.back.rony.backrony.monitoring.application.MonitorService;
import com.back.rony.backrony.monitoring.application.port.MonitorPort;
import com.back.rony.backrony.monitoring.application.port.VisionPort;
import com.back.rony.backrony.monitoring.infrastructure.MiraVisionAdapter;
import com.back.rony.backrony.monitoring.infrastructure.SimulatedVisionAdapter;
import com.back.rony.backrony.robotics.domain.RobotProfile;
import com.back.rony.backrony.robotics.infrastructure.HttpRobotAdapter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class ApplicationConfiguration {
    @Bean
    RobotProfile robotProfile(RonyProperties properties, ObjectMapper mapper) {
        return ProfileLoader.load(properties.runtimeProfile(), mapper);
    }

    @Bean(destroyMethod = "close")
    MonitorPort monitor(
            RonyProperties properties,
            HistoryPort history,
            ObjectMapper mapper,
            RobotProfile profile,
            @Value("${rony.execution-location:pc}") String executionLocation,
            @Value("${rony.device-id:jetmax-01}") String deviceId,
            @Value("${rony.browser-streaming-enabled:true}") boolean browserStreaming,
            @Value("${rony.record-demo-cycles:false}") boolean recordDemoCycles,
            @Value("${rony.allow-test-agent:false}") boolean allowTestAgent) {
        if ("cloud".equals(executionLocation)) {
            if (properties.accessToken() == null || properties.accessToken().length() < 24) {
                throw new IllegalArgumentException(
                        "Configura una clave web privada para el servidor.");
            }
            return new CloudDeviceRelay(history, profile, deviceId, browserStreaming);
        }
        if (!"pc".equals(executionLocation))
            throw new IllegalArgumentException("Ubicación de ejecución inválida.");
        VisionPort source;
        if ("live".equals(properties.sourceMode())) {
            var calibrationClient =
                    new HttpRobotAdapter(
                            properties.robotAgentUrl(),
                            properties.robotAgentToken(),
                            profile,
                            mapper,
                            allowTestAgent);
            source =
                    new MiraVisionAdapter(
                            properties,
                            mapper,
                            calibrationClient::readCalibration,
                            calibrationClient::close);
        } else {
            source = new SimulatedVisionAdapter(properties.framesPerSecond());
        }
        return new MonitorService(
                source,
                properties.sourceMode(),
                history,
                properties.physicalMotion() && "live".equals(properties.sourceMode())
                        ? () ->
                                new HttpRobotAdapter(
                                        properties.robotAgentUrl(),
                                        properties.robotAgentToken(),
                                        profile,
                                        mapper,
                                        allowTestAgent)
                        : null,
                recordDemoCycles,
                profile);
    }

    @Bean
    WorkerStartup workerStartup(MonitorPort monitor, RonyProperties properties) {
        return new WorkerStartup(monitor, properties);
    }

    record WorkerStartup(MonitorPort monitor, RonyProperties properties) {
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
