package com.devcollab.knowledgecore.auth.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldRegisterAndAccessCurrentUser() throws Exception {
        String username = uniqueUsername();
        String registerRequest = registerRequest(username);

        MvcResult registerResult = mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(registerRequest)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").exists())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        assertNotNull(registerResult.getResponse().getCookie("dc_refresh"));
        assertNotNull(registerResult.getResponse().getCookie("dc_csrf"));
        assertEquals(
                "/api/v1/auth",
                registerResult.getResponse().getCookie("dc_refresh").getPath()
        );
        assertEquals(
                "/",
                registerResult.getResponse().getCookie("dc_csrf").getPath()
        );

        JsonNode responseJson = objectMapper.readTree(
                registerResult.getResponse().getContentAsString()
        );
        String accessToken = responseJson.get("accessToken").asText();

        mockMvc.perform(
                        get("/api/v1/auth/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + accessToken
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").exists())
                .andExpect(jsonPath("$.username").value(username));
    }

    @Test
    void shouldRejectDuplicateUsername() throws Exception {
        String username = uniqueUsername();
        String registerRequest = registerRequest(username);

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(registerRequest)
                )
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(registerRequest)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_USERNAME_EXISTS"))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    void shouldRejectIncorrectPassword() throws Exception {
        String username = uniqueUsername();

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(registerRequest(username))
                )
                .andExpect(status().isCreated());

        String loginRequest = """
                {
                  "username": "%s",
                  "password": "wrong-password"
                }
                """.formatted(username);

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginRequest)
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void shouldRejectCurrentUserRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_INVALID"));
    }

    @Test
    void shouldRefreshAndRotateCookies() throws Exception {
        MvcResult registerResult = register(uniqueUsername());
        Cookie refreshCookie = registerResult
                .getResponse()
                .getCookie("dc_refresh");
        Cookie csrfCookie = registerResult
                .getResponse()
                .getCookie("dc_csrf");

        assertNotNull(refreshCookie);
        assertNotNull(csrfCookie);

        MvcResult refreshResult = mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .cookie(refreshCookie, csrfCookie)
                                .header("Origin", "http://localhost:5173")
                                .header("X-CSRF-Token", csrfCookie.getValue())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        Cookie rotatedRefreshCookie = refreshResult
                .getResponse()
                .getCookie("dc_refresh");
        Cookie rotatedCsrfCookie = refreshResult
                .getResponse()
                .getCookie("dc_csrf");

        assertNotNull(rotatedRefreshCookie);
        assertNotNull(rotatedCsrfCookie);
        assertNotEquals(
                refreshCookie.getValue(),
                rotatedRefreshCookie.getValue()
        );
        assertNotEquals(csrfCookie.getValue(), rotatedCsrfCookie.getValue());

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .cookie(refreshCookie, csrfCookie)
                                .header("Origin", "http://localhost:5173")
                                .header("X-CSRF-Token", csrfCookie.getValue())
                )
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.code").value("AUTH_REFRESH_INVALID")
                );
    }

    @Test
    void shouldRefreshFromSecondAllowedLocalOrigin() throws Exception {
        MvcResult registerResult = register(uniqueUsername());
        Cookie refreshCookie = registerResult
                .getResponse()
                .getCookie("dc_refresh");
        Cookie csrfCookie = registerResult
                .getResponse()
                .getCookie("dc_csrf");

        assertNotNull(refreshCookie);
        assertNotNull(csrfCookie);

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .cookie(refreshCookie, csrfCookie)
                                .header("Origin", "http://127.0.0.1:5173")
                                .header(
                                        "X-CSRF-Token",
                                        csrfCookie.getValue()
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void shouldRejectRefreshFromUntrustedOrigin() throws Exception {
        MvcResult registerResult = register(uniqueUsername());
        Cookie refreshCookie = registerResult
                .getResponse()
                .getCookie("dc_refresh");
        Cookie csrfCookie = registerResult
                .getResponse()
                .getCookie("dc_csrf");

        assertNotNull(refreshCookie);
        assertNotNull(csrfCookie);

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .cookie(refreshCookie, csrfCookie)
                                .header("Origin", "http://attacker.example")
                                .header(
                                        "X-CSRF-Token",
                                        csrfCookie.getValue()
                                )
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"));
    }

    @Test
    void shouldRejectRefreshWithInvalidCsrfHeader() throws Exception {
        MvcResult registerResult = register(uniqueUsername());
        Cookie refreshCookie = registerResult
                .getResponse()
                .getCookie("dc_refresh");
        Cookie csrfCookie = registerResult
                .getResponse()
                .getCookie("dc_csrf");

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .cookie(refreshCookie, csrfCookie)
                                .header("Origin", "http://localhost:5173")
                                .header("X-CSRF-Token", "wrong-csrf-token")
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"));
    }

    @Test
    void shouldLogoutAndRevokeCurrentRefreshSession() throws Exception {
        MvcResult registerResult = register(uniqueUsername());
        Cookie refreshCookie = registerResult
                .getResponse()
                .getCookie("dc_refresh");
        Cookie csrfCookie = registerResult
                .getResponse()
                .getCookie("dc_csrf");

        MvcResult logoutResult = mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .cookie(refreshCookie, csrfCookie)
                                .header("Origin", "http://localhost:5173")
                                .header("X-CSRF-Token", csrfCookie.getValue())
                )
                .andExpect(status().isNoContent())
                .andReturn();

        Cookie clearedRefreshCookie = logoutResult
                .getResponse()
                .getCookie("dc_refresh");
        Cookie clearedCsrfCookie = logoutResult
                .getResponse()
                .getCookie("dc_csrf");

        assertNotNull(clearedRefreshCookie);
        assertNotNull(clearedCsrfCookie);
        assertEquals(0, clearedRefreshCookie.getMaxAge());
        assertEquals(0, clearedCsrfCookie.getMaxAge());

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .cookie(refreshCookie, csrfCookie)
                                .header("Origin", "http://localhost:5173")
                                .header("X-CSRF-Token", csrfCookie.getValue())
                )
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.code").value("AUTH_REFRESH_INVALID")
                );
    }

    @Test
    void shouldRejectLogoutWithInvalidCsrfHeader() throws Exception {
        MvcResult registerResult = register(uniqueUsername());
        Cookie refreshCookie = registerResult
                .getResponse()
                .getCookie("dc_refresh");
        Cookie csrfCookie = registerResult
                .getResponse()
                .getCookie("dc_csrf");

        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .cookie(refreshCookie, csrfCookie)
                                .header("Origin", "http://localhost:5173")
                                .header("X-CSRF-Token", "wrong-csrf-token")
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"));
    }

    private String uniqueUsername() {
        return "alice_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private MvcResult register(String username) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(registerRequest(username))
                )
                .andExpect(status().isCreated())
                .andReturn();
    }

    private String registerRequest(String username) {
        return """
                {
                  "username": "%s",
                  "displayName": "Alice",
                  "password": "password123"
                }
                """.formatted(username);
    }
}
