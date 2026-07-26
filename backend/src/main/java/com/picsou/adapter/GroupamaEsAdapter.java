package com.picsou.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.GroupamaEsErrorCode;
import com.picsou.port.GroupamaEsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
public class GroupamaEsAdapter implements GroupamaEsPort {
    private static final Logger log = LoggerFactory.getLogger(GroupamaEsAdapter.class);
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(100);
    private static final Duration DEFAULT_PORTFOLIO_TIMEOUT = Duration.ofSeconds(150);
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final Duration authTimeout;
    private final Duration portfolioTimeout;

    @Autowired
    public GroupamaEsAdapter(
        @Value("${app.groupama-es-auth.url:http://groupama-es-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(
            WebClient.builder()
                .baseUrl(url)
                .codecs(configurer ->
                    configurer.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES)
                )
                .build(),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_PORTFOLIO_TIMEOUT
        );
    }

    GroupamaEsAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_AUTH_TIMEOUT, DEFAULT_PORTFOLIO_TIMEOUT);
    }

    GroupamaEsAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration portfolioTimeout
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.authTimeout = authTimeout;
        this.portfolioTimeout = portfolioTimeout;
    }

    @Override
    public InitiateResult initiateAuth(String login, String password) {
        return post(
            "/initiate",
            Map.of("login", login, "password", password),
            InitiateResult.class,
            "Could not initiate Groupama ES authentication",
            GroupamaEsErrorCode.INVALID_CREDENTIALS
        );
    }

    @Override
    public String completeAuth(String processId, String code) {
        SessionResponse response = post(
            "/complete",
            Map.of("processId", processId, "code", code),
            SessionResponse.class,
            "Could not complete Groupama ES authentication",
            GroupamaEsErrorCode.INVALID_OTP
        );
        return response.sessionState();
    }

    @Override
    public List<AccountData> fetchAccounts(String sessionState) {
        try {
            AccountData[] response = client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sessionState", sessionState))
                .retrieve().bodyToMono(AccountData[].class)
                .timeout(portfolioTimeout).block();
            if (response == null || response.length == 0) {
                throw coded(
                    GroupamaEsErrorCode.PORTFOLIO_INCOMPLETE,
                    "Groupama ES returned no complete savings accounts",
                    null
                );
            }
            return List.of(response);
        } catch (RuntimeException ex) {
            throw mapError("Could not fetch Groupama ES portfolio", ex, null);
        }
    }

    private <T> T post(
        String path,
        Object body,
        Class<T> type,
        String message,
        GroupamaEsErrorCode authenticationFailure
    ) {
        try {
            T response = client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(type)
                .timeout(authTimeout).block();
            if (response == null) {
                throw coded(GroupamaEsErrorCode.UPSTREAM_UNAVAILABLE, message, null);
            }
            return response;
        } catch (RuntimeException ex) {
            throw mapError(message, ex, authenticationFailure);
        }
    }

    private SyncException mapError(
        String message,
        RuntimeException ex,
        GroupamaEsErrorCode authenticationFailure
    ) {
        if (ex instanceof SyncException sync) {
            return sync;
        }
        if (causedByTimeout(ex)) {
            log.warn("{}: sidecar request timed out", message);
            return coded(
                GroupamaEsErrorCode.UPSTREAM_UNAVAILABLE,
                "Groupama ES took too long to respond. Please try again.",
                ex
            );
        }
        if (ex instanceof WebClientResponseException response) {
            GroupamaEsErrorCode upstreamCode = responseCode(response);
            if (upstreamCode != null) {
                return coded(upstreamCode, friendlyMessage(upstreamCode), ex);
            }
            if (response.getStatusCode().value() == 401 && authenticationFailure != null) {
                return coded(authenticationFailure, friendlyMessage(authenticationFailure), ex);
            }
            if (response.getStatusCode().value() == 410) {
                return coded(
                    GroupamaEsErrorCode.AUTH_ATTEMPT_EXPIRED,
                    friendlyMessage(GroupamaEsErrorCode.AUTH_ATTEMPT_EXPIRED),
                    ex
                );
            }
            if (response.getStatusCode().is5xxServerError()) {
                log.warn("Groupama ES sidecar returned status {}", response.getStatusCode().value());
                return coded(
                    GroupamaEsErrorCode.UPSTREAM_UNAVAILABLE,
                    friendlyMessage(GroupamaEsErrorCode.UPSTREAM_UNAVAILABLE),
                    ex
                );
            }
        }
        log.error(message, ex);
        return coded(GroupamaEsErrorCode.UPSTREAM_UNAVAILABLE, message, ex);
    }

    private GroupamaEsErrorCode responseCode(WebClientResponseException response) {
        try {
            JsonNode detailNode = objectMapper
                .readTree(response.getResponseBodyAsString())
                .path("detail");
            if (!detailNode.isTextual() || detailNode.asText().isBlank()) {
                return null;
            }
            try {
                return GroupamaEsErrorCode.valueOf(detailNode.asText());
            } catch (IllegalArgumentException ex) {
                log.warn("Groupama ES sidecar returned an unknown error code");
                return null;
            }
        } catch (JsonProcessingException ex) {
            log.warn(
                "Could not parse Groupama ES sidecar error response (status={})",
                response.getStatusCode().value()
            );
            return null;
        }
    }

    private boolean causedByTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String friendlyMessage(GroupamaEsErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS -> "Groupama ES rejected the credentials";
            case INVALID_OTP -> "Groupama ES rejected the verification code";
            case AUTH_ATTEMPT_EXPIRED -> "The Groupama ES authentication attempt expired";
            case SESSION_EXPIRED -> "The Groupama ES session expired";
            case ACTION_REQUIRED -> "Groupama ES requires an action in its customer portal";
            case PORTFOLIO_INCOMPLETE -> "Groupama ES returned an incomplete portfolio";
            case UPSTREAM_FORMAT_CHANGED -> "The Groupama ES website format changed";
            case INVALID_DATA -> "Groupama ES returned invalid portfolio data";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "Groupama ES is temporarily unavailable";
        };
    }

    private SyncException coded(GroupamaEsErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private record SessionResponse(String sessionState) {}
}
