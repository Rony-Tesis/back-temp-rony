package com.back.rony.backrony;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "rony.access-token=012345678901234567890123")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityContractTest {
    @Autowired MockMvc mvc;

    @Test
    void apiAndOpenapiRequireTokenButInformationIsPublic() throws Exception {
        mvc.perform(get("/api/v1/monitor/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isUnauthorized());
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(
                        get("/api/v1/monitor/status")
                                .header("Authorization", "Bearer 012345678901234567890123"))
                .andExpect(status().isOk());
    }

    @Test
    void loginUsesHttpOnlyStrictCookieAndRejectsIncorrectToken() throws Exception {
        mvc.perform(
                        post("/api/v1/session")
                                .header("X-Rony-Control", "1")
                                .contentType("application/json")
                                .content("{\"token\":\"012345678901234567890123\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Set-Cookie",
                                        org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(
                        header().string(
                                        "Set-Cookie",
                                        org.hamcrest.Matchers.containsString("SameSite=Strict")));
        mvc.perform(
                        post("/api/v1/session")
                                .header("X-Rony-Control", "1")
                                .contentType("application/json")
                                .content("{\"token\":\"xxxxxxxxxxxxxxxxxxxxxxxx\"}"))
                .andExpect(status().isUnauthorized());
    }
}
