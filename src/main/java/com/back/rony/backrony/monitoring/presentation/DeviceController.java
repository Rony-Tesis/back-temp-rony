package com.back.rony.backrony.monitoring.presentation;

import com.back.rony.backrony.monitoring.application.CloudDeviceRelay;
import com.back.rony.backrony.monitoring.application.port.MonitorPort;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@ConditionalOnProperty(name = "rony.execution-location", havingValue = "cloud")
public final class DeviceController {
    private final CloudDeviceRelay relay;
    private final ObjectMapper mapper;

    public DeviceController(MonitorPort relay, ObjectMapper mapper) {
        this.relay = (CloudDeviceRelay) relay;
        this.mapper = mapper;
    }

    @PostMapping(value = "/api/v1/device/exchange", consumes = "application/json")
    public CloudDeviceRelay.Reply exchange(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(1024 * 1024 + 1);
        if (bytes.length > 1024 * 1024)
            throw new IllegalArgumentException("Paquete del dispositivo demasiado grande.");
        return relay.exchange(mapper.readValue(bytes, CloudDeviceRelay.Exchange.class));
    }
}
