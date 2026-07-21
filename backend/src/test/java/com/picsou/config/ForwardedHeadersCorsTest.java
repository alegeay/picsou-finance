package com.picsou.config;

import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import com.picsou.service.SetupService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Regression test for the "403 over HTTPS behind a reverse proxy" bug.
 *
 * <p>Picsou serves the SPA and the API from the same origin, so login/setup requests are
 * genuinely same-origin and must never be subject to CORS. Spring's
 * {@link org.springframework.web.cors.CorsUtils#isCorsRequest} decides same-vs-cross origin by
 * comparing the {@code Origin} header's scheme/host/port against {@code request.getScheme()/
 * getServerName()/getServerPort()}. Behind a TLS-terminating proxy the browser sends
 * {@code Origin: https://host} while the backend connection is plain HTTP, so without
 * {@code server.forward-headers-strategy=framework} the scheme mismatches, the request is
 * treated as cross-origin, and the fail-closed allow-list rejects it with 403.
 *
 * <p>These tests run the real {@link ForwardedHeaderFilter} + {@link DynamicCorsConfigurationSource}
 * + {@link LoggingCorsProcessor} the application wires in production. The allow-list deliberately
 * holds only the {@code http://} origin (the value persisted when setup is run over HTTP), which is
 * the exact state in which users hit the bug.
 */
@ExtendWith(MockitoExtension.class)
class ForwardedHeadersCorsTest {

    private static final String PUBLIC_HOST = "picsou.example.com";
    private static final String HTTPS_ORIGIN = "https://" + PUBLIC_HOST;

    @Mock AppSettingRepository settingRepository;

    /** Allow-list holds only the HTTP origin — what the wizard stores during an HTTP setup. */
    private void allowListHasOnlyHttpOrigin() {
        AppSetting stored = AppSetting.builder()
            .key(SetupService.KEY_CORS_ALLOWED_ORIGINS)
            .value("http://" + PUBLIC_HOST)
            .build();
        when(settingRepository.findByKey(SetupService.KEY_CORS_ALLOWED_ORIGINS))
            .thenReturn(Optional.of(stored));
    }

    @Test
    void withForwardedProtoHttps_sameOriginRequestIsNotRejected() throws Exception {
        allowListHasOnlyHttpOrigin();

        // The proxy forwarded the real scheme — the fix relies on the backend honoring it.
        MockHttpServletResponse response = process(postBehindProxy("https"));

        // Recognized as same-origin -> CORS skipped -> reaches the controller (here: not 403).
        assertThat(response.getStatus()).isNotEqualTo(403);
    }

    @Test
    void withoutForwardedProto_sameOriginRequestIsWronglyRejected() throws Exception {
        allowListHasOnlyHttpOrigin();

        // Reproduces the bug: no X-Forwarded-Proto -> backend sees http -> scheme mismatch.
        MockHttpServletResponse response = process(postBehindProxy(null));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void genuineCrossOriginRequestIsStillRejected() throws Exception {
        allowListHasOnlyHttpOrigin();

        MockHttpServletRequest req = postBehindProxy("https");
        req.removeHeader("Origin");
        req.addHeader("Origin", "https://evil.example.com"); // different host -> truly cross-origin

        assertThat(process(req).getStatus()).isEqualTo(403);
    }

    /**
     * Build the request as it reaches the backend after nginx: a plain-HTTP connection on the
     * internal port, the public Host header, the browser's HTTPS Origin, and (optionally) the
     * forwarded scheme. No X-Forwarded-Port is sent — the backend derives 443 from the scheme.
     */
    private MockHttpServletRequest postBehindProxy(String forwardedProto) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("POST");
        req.setRequestURI("/api/setup/security");
        req.setScheme("http");
        req.setServerName("127.0.0.1");
        req.setServerPort(9090);
        req.addHeader("Host", PUBLIC_HOST);
        req.addHeader("Origin", HTTPS_ORIGIN);
        if (forwardedProto != null) {
            req.addHeader("X-Forwarded-Proto", forwardedProto);
        }
        return req;
    }

    /** Run the production filter + CORS processing chain and return the response. */
    private MockHttpServletResponse process(MockHttpServletRequest req) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        new ForwardedHeaderFilter().doFilter(req, new MockHttpServletResponse(), chain);
        HttpServletRequest effective = (HttpServletRequest) chain.getRequest();

        DynamicCorsConfigurationSource source =
            new DynamicCorsConfigurationSource(settingRepository, "");
        CorsConfiguration config = source.getCorsConfiguration(effective);

        MockHttpServletResponse response = new MockHttpServletResponse();
        new LoggingCorsProcessor().processRequest(config, effective, response);
        return response;
    }
}
