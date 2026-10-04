package com.back.rony.backrony;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.rony.backrony.monitoring.application.MonitorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired MonitorService monitor;

    @Test
    void snapshotKeepsExistingAngularFieldsAndPhysicalObjectIsNull() throws Exception {
        mvc.perform(get("/api/v1/monitor/snapshot"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.updatedAt")
                                .value(
                                        org.hamcrest.Matchers.matchesPattern(
                                                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z")))
                .andExpect(jsonPath("$.motionMode").value("simulation"))
                .andExpect(jsonPath("$.robotState").isEmpty())
                .andExpect(jsonPath("$.process.stageDurationsMs.length()").value(6))
                .andExpect(jsonPath("$.equipment.length()").value(5))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void unavailableCameraReturns503WithRetryHint() throws Exception {
        mvc.perform(get("/api/v1/monitor/frame"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "2"));
        mvc.perform(get("/api/v1/monitor/stream")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void physicalCommandsRemainDisabledInSimulation() throws Exception {
        mvc.perform(
                        post("/api/v1/robot/commands")
                                .header("X-Rony-Control", "1")
                                .contentType("application/json")
                                .content("{\"action\":\"arm\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void writesRequireControlHeaderAndMatchingOrigin() throws Exception {
        mvc.perform(
                        post("/api/v1/simulation/commands")
                                .contentType("application/json")
                                .content("{\"action\":\"pause\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/simulation/commands")
                                .header("X-Rony-Control", "1")
                                .header("Origin", "http://evil.invalid")
                                .contentType("application/json")
                                .content("{\"action\":\"pause\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownActionsExtraPropertiesAndInvalidJsonReturn422() throws Exception {
        for (String body :
                new String[] {
                    "{\"action\":\"anything\"}", "{\"action\":\"pause\",\"x\":1}", "{}", "{"
                }) {
            mvc.perform(
                            post("/api/v1/simulation/commands")
                                    .header("X-Rony-Control", "1")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isUnprocessableContent());
        }
    }

    @Test
    void pauseAndResumePreserveResponseShape() throws Exception {
        mvc.perform(
                        post("/api/v1/simulation/commands")
                                .header("X-Rony-Control", "1")
                                .contentType("application/json")
                                .content("{\"action\":\"pause\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true));
        mvc.perform(
                        post("/api/v1/simulation/commands")
                                .header("X-Rony-Control", "1")
                                .contentType("application/json")
                                .content("{\"action\":\"resume\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false));
    }

    @Test
    void openapiListsAllExistingRoutesAndCommandSchemas() throws Exception {
        mvc.perform(get("/api/v1/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/robot/commands'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/monitor/events'].get").exists())
                .andExpect(jsonPath("$.components.schemas.PhysicalCommand").exists());
    }

    @Test
    void unconfiguredSessionDoesNotInventAuthentication() throws Exception {
        mvc.perform(
                        post("/api/v1/session")
                                .header("X-Rony-Control", "1")
                                .contentType("application/json")
                                .content("{\"token\":\"012345678901234567890123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void frameworkErrorsKeepTheirHttpMeaning() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/monitor/status").header("X-Rony-Control", "1"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(
                        post("/api/v1/simulation/commands")
                                .header("X-Rony-Control", "1")
                                .contentType("text/plain")
                                .content("pause"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
