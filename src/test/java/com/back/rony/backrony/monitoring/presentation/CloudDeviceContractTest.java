package com.back.rony.backrony.monitoring.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.rony.backrony.monitoring.application.CloudDeviceRelay;
import com.back.rony.backrony.monitoring.application.CloudDeviceRelayTest;
import com.back.rony.backrony.monitoring.application.port.MonitorPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        properties = {
            "rony.execution-location=cloud",
            "rony.access-token=cloud-web-test-token-0123456789012345",
            "rony.device-token=cloud-device-test-token-0123456789012345",
            "rony.camera-public-url=https://camera-api.example.invalid",
            "rony.frontend-url=https://camera-web.example.invalid"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CloudDeviceContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MonitorPort monitor;
    private static final String WEB = "Bearer cloud-web-test-token-0123456789012345";
    private static final String DEVICE = "Bearer cloud-device-test-token-0123456789012345";

    @Test
    void cameraCapabilityCannotControlRobotAndOnlyConfiguredOriginCanReadStream() throws Exception {
        mvc.perform(get("/api/v1/monitor/camera-access")).andExpect(status().isUnauthorized());
        var grant =
                mvc.perform(get("/api/v1/monitor/camera-access").header("Authorization", WEB))
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.url")
                                        .value(
                                                "https://camera-api.example.invalid/api/v1/monitor/camera-stream"))
                        .andReturn();
        var ticket =
                mapper.readTree(grant.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(
                        post("/api/v1/robot/commands")
                                .header("Authorization", "Camera " + ticket)
                                .header("X-Rony-Control", "1")
                                .contentType("application/json")
                                .content("{\"action\":\"arm\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        options("/api/v1/monitor/camera-stream")
                                .header("Origin", "https://camera-web.example.invalid")
                                .header("Access-Control-Request-Method", "GET")
                                .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Access-Control-Allow-Origin",
                                        "https://camera-web.example.invalid"));
        mvc.perform(
                        get("/api/v1/monitor/camera-stream")
                                .header("Origin", "https://other.example.invalid")
                                .header("Authorization", "Camera " + ticket))
                .andExpect(status().isForbidden());
        // No camera in this test: authenticated request reaches availability check, then ticket
        // expires by use.
        mvc.perform(
                        get("/api/v1/monitor/camera-stream")
                                .header("Authorization", "Camera " + ticket))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(
                        get("/api/v1/monitor/camera-stream")
                                .header("Authorization", "Camera " + ticket))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/monitor/camera-stream").header("Authorization", WEB))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cloudStartsWithoutNativePythonAndBrowserCannotImpersonateRobot() throws Exception {
        org.junit.jupiter.api.Assertions.assertInstanceOf(CloudDeviceRelay.class, monitor);
        mvc.perform(
                        post("/api/v1/device/exchange")
                                .contentType("application/json")
                                .header("Authorization", WEB)
                                .header("X-Rony-Control", "1")
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/device/exchange")
                                .contentType("application/json")
                                .header("Authorization", DEVICE)
                                .header("Host", "localhost")
                                .header("Origin", "http://localhost")
                                .header("X-Rony-Control", "1")
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/monitor/status").header("Authorization", DEVICE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void robotInitiatesExchangeAndWebReadsCompatibleSnapshot() throws Exception {
        mvc.perform(get("/api/v1/device/exchange").header("Authorization", DEVICE))
                .andExpect(status().isMethodNotAllowed());
        var packet =
                CloudDeviceRelayTest.packet(UUID.randomUUID().toString(), 1, List.of(), List.of());
        mvc.perform(
                        post("/api/v1/device/exchange")
                                .contentType("application/json")
                                .header("Authorization", DEVICE)
                                .header("X-Rony-Control", "1")
                                .content(mapper.writeValueAsString(packet)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlLeaseSeconds").value(15));
        mvc.perform(get("/api/v1/monitor/snapshot").header("Authorization", WEB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.robotState.connected").value(true))
                .andExpect(jsonPath("$.motionMode").value("physical"));
    }
}
