package io.github.jjackson0118.doraloop.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write endpoints require the shared secret; the read endpoint does not.
 *
 * <p>This service is going on the public internet, where an unauthenticated
 * ingest means anyone can write its delivery metrics. That is not defacement,
 * it is a number that is quietly wrong, which is the thing this project argues
 * is worse than no number at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dora.ingest.token=correct-horse-battery-staple")
@Testcontainers
class IngestAuthTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String TOKEN = "correct-horse-battery-staple";

    @Autowired TestRestTemplate http;
    // Qualified: Spring registers more than one HandlerMapping of this type.
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void aWriteWithNoTokenIsRefused() {
        ResponseEntity<String> res = post("/api/v1/deployments", deploymentJson(), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains(IngestAuthFilter.HEADER);
    }

    @Test
    void aWriteWithTheWrongTokenIsRefused() {
        assertThat(post("/api/v1/deployments", deploymentJson(), "not-the-token")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/v1/incidents", incidentJson(), "not-the-token")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * A prefix of the real token is refused.
     *
     * <p>Cheap, and it is the assertion that fails first if the comparison is
     * ever rewritten as {@code startsWith} or a loop with an early return --
     * the shape that leaks a secret one character at a time to anyone who can
     * time the response.
     */
    @Test
    void aPrefixOfTheTokenIsRefused() {
        assertThat(post("/api/v1/deployments", deploymentJson(), TOKEN.substring(0, TOKEN.length() - 1))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/v1/deployments", deploymentJson(), TOKEN + "x")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aWriteWithTheTokenIsAccepted() {
        assertThat(post("/api/v1/deployments", deploymentJson(), TOKEN)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /**
     * The report stays readable without a token, deliberately.
     *
     * <p>Publishing the report is the point of deploying this at all. If that
     * ever changes it should be a decision someone makes, not something that
     * drifts in behind a filter widened to "/api/v1/**".
     */
    @Test
    void theReportIsReadableWithoutAToken() {
        assertThat(http.getForEntity("/api/v1/services/" + UUID.randomUUID() + "/report?window=P30D",
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Every mapped write endpoint is behind the filter, enumerated rather than
     * remembered.
     *
     * <p>A test naming {@code /deployments} and {@code /incidents} passes
     * forever after someone adds a third POST. This asks Spring for the
     * handlers it actually mapped, and fails on any POST under
     * {@code /api/v1/} that answers without a token -- so the next write
     * endpoint is covered on the day it is written, by a test nobody had to
     * remember to update.
     */
    @Test
    void everyMappedWriteEndpointRequiresTheToken() {
        List<String> unprotected = new ArrayList<>();
        List<String> checked = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            boolean isPost = info.getMethodsCondition().getMethods().stream()
                    .anyMatch(m -> m.name().equals("POST"));
            if (!isPost) continue;
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/v1/")) continue;
                checked.add(pattern);
                ResponseEntity<String> res = post(pattern, "{}", null);
                if (res.getStatusCode() != HttpStatus.FORBIDDEN) {
                    unprotected.add(pattern + " -> " + res.getStatusCode());
                }
            }
        }

        // An empty scan is not a pass: if the mapping lookup ever stops finding
        // the write endpoints, this test would otherwise report success over
        // nothing.
        assertThat(checked).as("POST endpoints under /api/v1/ that were examined").isNotEmpty();
        assertThat(unprotected).as("write endpoints answering without a token").isEmpty();
    }

    // --- helpers -----------------------------------------------------------

    private ResponseEntity<String> post(String path, String json, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.set(IngestAuthFilter.HEADER, token);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(json, h), String.class);
    }

    private static String deploymentJson() {
        Instant t = Instant.now().minus(1, ChronoUnit.HOURS);
        return """
                {"id":"%s","service":"auth-%s","environment":"production","deployedAt":"%s",
                 "outcome":"SUCCESS","changes":[{"commitSha":"aaa","authoredAt":"%s"}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), t, t.minus(2, ChronoUnit.HOURS));
    }

    private static String incidentJson() {
        Instant t = Instant.now().minus(2, ChronoUnit.HOURS);
        return """
                {"id":"%s","service":"auth-%s","causedByCommitSha":null,"detectedAt":"%s","resolvedAt":null}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), t);
    }
}
