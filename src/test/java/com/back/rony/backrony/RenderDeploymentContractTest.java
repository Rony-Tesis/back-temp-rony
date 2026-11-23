package com.back.rony.backrony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.rony.backrony.shared.infrastructure.ProfileLoader;
import java.nio.file.Path;
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
            "rony.access-token=render-web-test-token-0123456789012345",
            "rony.device-token=render-device-test-token-0123456789012345",
            "rony.frontend-url=https://rony-brazo.netlify.app",
            "rony.allowed-hosts=localhost,rony-test.onrender.com,rony-brazo.netlify.app"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RenderDeploymentContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void healthIsPublicWhileRobotApiStillRequiresAuthentication() throws Exception {
        mvc.perform(get("/health").header("Host", "rony-test.onrender.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("up"));
        mvc.perform(get("/api/v1/monitor/status").header("Host", "rony-test.onrender.com"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/health")
                                .header("Host", "attacker.invalid")
                                .with(
                                        request -> {
                                            request.setServerName("attacker.invalid");
                                            return request;
                                        }))
                .andExpect(status().isBadRequest());
    }

    @Test
    void netlifyProxyCanLoginButOtherOriginsAndMissingControlHeadersAreRejected() throws Exception {
        String body = "{\"token\":\"render-web-test-token-0123456789012345\"}";
        mvc.perform(
                        post("/api/v1/session")
                                .secure(true)
                                .header("Host", "rony-test.onrender.com")
                                .header("Origin", "https://rony-brazo.netlify.app")
                                .header("X-Rony-Control", "1")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Set-Cookie",
                                        org.hamcrest.Matchers.containsString("Secure")))
                .andExpect(
                        header().string(
                                        "Set-Cookie",
                                        org.hamcrest.Matchers.containsString("SameSite=Strict")));
        for (String origin :
                new String[] {
                    "https://attacker.invalid",
                    "https://rony-brazo.netlify.app.attacker.invalid",
                    "http://rony-brazo.netlify.app"
                }) {
            mvc.perform(
                            post("/api/v1/session")
                                    .header("Host", "rony-test.onrender.com")
                                    .header("Origin", origin)
                                    .header("X-Rony-Control", "1")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(
                        post("/api/v1/session")
                                .header("Host", "rony-test.onrender.com")
                                .header("Origin", "https://rony-brazo.netlify.app")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void independentBackendContainsTheSameNotebookProfile() {
        assertEquals(
                TestProfile.profile().sha256(),
                ProfileLoader.load(Path.of("deploy/rony_profile.json"), mapper).sha256());
    }
}
