package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.exception.SyncException;
import com.picsou.model.GroupamaEsSyncStatus;
import com.picsou.port.GroupamaEsErrorCode;
import com.picsou.service.GroupamaEsSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class GroupamaEsControllerTest {
    private static final Long MEMBER_ID = 7L;

    @Mock GroupamaEsSyncService service;
    @Mock UserContext userContext;
    @Mock HttpServletRequest request;

    private GroupamaEsController controller;
    private ConcurrentHashMap<String, Bucket> authBuckets;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        controller = new GroupamaEsController(service, userContext, authBuckets);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    @Test
    void initiateScopesCredentialsToTheCurrentMember() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        var expected = new GroupamaEsSyncService.AuthInitResponse(
            "process",
            true,
            "OTP"
        );
        when(service.initiateAuth("login", "password", MEMBER_ID))
            .thenReturn(expected);

        var response = controller.initiate(
            new GroupamaEsController.InitiateRequest("login", "password"),
            request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
        verify(service).initiateAuth("login", "password", MEMBER_ID);
    }

    @Test
    void authenticationRateLimitUsesTheTrustedProxyClientIp() {
        var proxiedRequest = new MockHttpServletRequest();
        proxiedRequest.setRemoteAddr("172.18.0.2");
        proxiedRequest.addHeader("X-Forwarded-For", "1.2.3.4");
        proxiedRequest.addHeader("X-Real-IP", "203.0.113.9");
        when(service.initiateAuth("login", "password", MEMBER_ID))
            .thenReturn(new GroupamaEsSyncService.AuthInitResponse(
                "process",
                true,
                "OTP"
            ));

        controller.initiate(
            new GroupamaEsController.InitiateRequest("login", "password"),
            proxiedRequest
        );

        assertThat(authBuckets).containsOnlyKeys("203.0.113.9");
    }

    @Test
    void completeAuthAcceptsGroupamaFourDigitCodesAndScopesTheMember()
        throws Exception {
        var queued = sessionStatus(GroupamaEsSyncStatus.QUEUED);
        when(service.completeAuth("process-123", "1234", MEMBER_ID))
            .thenReturn(queued);

        mockMvc.perform(post("/api/groupama-es/auth/complete")
                .with(httpRequest -> {
                    httpRequest.setRemoteAddr("127.0.0.1");
                    return httpRequest;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process-123\",\"code\":\"1234\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncStatus").value("QUEUED"));

        verify(service).completeAuth("process-123", "1234", MEMBER_ID);
    }

    @Test
    void invalidOtpErrorCodeIsExposedWithoutCredentials() throws Exception {
        when(service.completeAuth("process-123", "000000", MEMBER_ID)).thenThrow(
            new SyncException(
                "Groupama ES rejected the verification code",
                null,
                GroupamaEsErrorCode.INVALID_OTP.name()
            )
        );

        mockMvc.perform(post("/api/groupama-es/auth/complete")
                .with(httpRequest -> {
                    httpRequest.setRemoteAddr("127.0.0.1");
                    return httpRequest;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process-123\",\"code\":\"000000\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_OTP"))
            .andExpect(jsonPath("$.detail").value(
                "Groupama ES rejected the verification code"
            ));
    }

    @Test
    void payloadValidationRejectsOversizedLoginAndMalformedOtp() throws Exception {
        mockMvc.perform(post("/api/groupama-es/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + "x".repeat(101)
                    + "\",\"password\":\"secret\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.login").exists());

        mockMvc.perform(post("/api/groupama-es/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process\",\"code\":\"12ab\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.code").exists());

        verify(service, times(0)).initiateAuth(anyString(), anyString(), anyLong());
        verify(service, times(0)).completeAuth(anyString(), anyString(), anyLong());
    }

    @Test
    void sixthAuthenticationAttemptFromTheSameIpIsRateLimited() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.initiateAuth("login", "password", MEMBER_ID)).thenReturn(
            new GroupamaEsSyncService.AuthInitResponse("process", true, "OTP")
        );

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(controller.initiate(
                new GroupamaEsController.InitiateRequest("login", "password"),
                request
            ).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(controller.initiate(
            new GroupamaEsController.InitiateRequest("login", "password"),
            request
        ).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(5))
            .initiateAuth("login", "password", MEMBER_ID);
    }

    @Test
    void syncStatusAndClearUseOnlyTheCurrentMember() {
        var queued = sessionStatus(GroupamaEsSyncStatus.QUEUED);
        when(service.queueSync(MEMBER_ID)).thenReturn(queued);
        when(service.getStatus(MEMBER_ID)).thenReturn(queued);

        assertThat(controller.sync().getStatusCode())
            .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.status()).isSameAs(queued);
        assertThat(controller.clear().getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).queueSync(MEMBER_ID);
        verify(service).getStatus(MEMBER_ID);
        verify(service).clearSession(MEMBER_ID);
    }

    private GroupamaEsSyncService.SessionStatusResponse sessionStatus(
        GroupamaEsSyncStatus syncStatus
    ) {
        return new GroupamaEsSyncService.SessionStatusResponse(
            true,
            null,
            syncStatus,
            null,
            null,
            null
        );
    }
}
