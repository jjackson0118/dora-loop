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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /**
     * The methods that may pass without the token. Stated as the safe list, not
     * the write list, so a method nobody enumerated is a write by default.
     */
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    @Autowired TestRestTemplate http;
    // Every RequestMappingInfoHandlerMapping, not just the application's: the
    // actuator registers its own, and its endpoints were invisible to the
    // earlier version of the sweep.
    @Autowired ApplicationContext ctx;
    @LocalServerPort int port;

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
     * Every mapped write endpoint is behind the filter -- enumerated rather
     * than remembered, and enumerated without a path filter.
     *
     * <p>A test naming {@code /deployments} and {@code /incidents} passes
     * forever after someone adds a third POST, so this asks Spring for the
     * handlers it actually mapped. The first version then discarded every
     * pattern outside {@code /api/v1/}, which made it useless for its stated
     * purpose: it encoded the same prefix assumption as the filter it was
     * checking, so it could confirm the filter's scope but never question it. A
     * guard that shares the belief of the thing it guards is not a second
     * opinion.
     *
     * <p>It now sweeps <em>every</em> {@code RequestMappingInfoHandlerMapping}
     * in the context -- which includes the actuator's, a separate mapping the
     * old version could not see at all -- and every method that is not GET,
     * HEAD or OPTIONS, at any path. Path variables are filled with a literal so
     * a templated pattern is actually requested rather than skipped.
     */
    @Test
    void everyMappedWriteEndpointRequiresTheToken() {
        List<String> unprotected = new ArrayList<>();
        List<String> checked = new ArrayList<>();

        for (RequestMappingInfoHandlerMapping mapping :
                ctx.getBeansOfType(RequestMappingInfoHandlerMapping.class).values()) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> e
                    : mapping.getHandlerMethods().entrySet()) {
                RequestMappingInfo info = e.getKey();
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                // A mapping with no method condition answers every method,
                // writes included; treat it as a write rather than assuming.
                Set<String> writes = methods.isEmpty()
                        ? Set.of("POST")
                        : methods.stream().map(Enum::name)
                                .filter(m -> !SAFE.contains(m))
                                .collect(Collectors.toCollection(LinkedHashSet::new));
                if (writes.isEmpty()) continue;

                for (String pattern : info.getPatternValues()) {
                    String path = pattern.replaceAll("\\{[^}]*}", "probe");
                    for (String method : writes) {
                        checked.add(method + " " + path);
                        ResponseEntity<String> res = http.exchange(
                                path, HttpMethod.valueOf(method),
                                new HttpEntity<>("{}", jsonHeaders(null)), String.class);
                        // 403 is the filter refusing. 503 would be the filter
                        // refusing an unconfigured service, also closed -- but
                        // this context is configured, so anything else means
                        // the request reached a handler unauthenticated.
                        if (res.getStatusCode() != HttpStatus.FORBIDDEN) {
                            unprotected.add(method + " " + path + " -> " + res.getStatusCode());
                        }
                    }
                }
            }
        }

        // An empty scan is not a pass: if the mapping lookup ever stops finding
        // the write endpoints, this test would otherwise report success over
        // nothing.
        assertThat(checked).as("write endpoints that were examined").isNotEmpty();
        assertThat(unprotected).as("write endpoints answering without a token").isEmpty();
    }

    /**
     * The filter cannot be skipped by rewriting the path.
     *
     * <p>Sent over a raw socket on purpose: an HTTP client normalises the
     * request target before it goes on the wire, so a test written with one
     * proves what the client does, not what the server accepts. These are the
     * spellings that made the previous prefix-matching filter skip itself --
     * they were refused only because the dispatcher independently answered 404,
     * and a dispatcher that grew more forgiving about leading slashes or case
     * would have turned a coincidence into an open write endpoint.
     */
    @Test
    void pathVariantsCannotSkipTheFilter() throws Exception {
        List<String> variants = List.of(
                "POST /api/v1/deployments",
                "POST //api/v1/deployments",
                "POST /./api/v1/deployments",
                "POST /foo/../api/v1/deployments",
                "POST /api/v1/../v1/deployments",
                "POST /api/v1//deployments",
                "POST /api/v1/deployments/",
                "POST /api/v1/deployments;a=b",
                "POST /API/V1/deployments",
                "POST /api/v1/%64eployments",
                "PUT /api/v1/deployments",
                "PATCH /api/v1/deployments",
                "DELETE /api/v1/deployments");

        String probeService = "pathvariant-" + UUID.randomUUID();
        List<String> reachedAHandler = new ArrayList<>();
        for (String variant : variants) {
            String status = rawRequestStatus(variant, deploymentJson(probeService));
            // 403 (refused by the filter) is the only acceptable answer. 404
            // would mean the dispatcher refused it and the filter never had an
            // opinion -- which is the coincidence this test exists to remove.
            if (!status.contains(" 403")) {
                reachedAHandler.add(variant + " -> " + status);
            }
        }
        assertThat(reachedAHandler)
                .as("path spellings the token filter did not refuse")
                .isEmpty();

        // The denominator: nothing was stored by any of them.
        assertThat(http.getForObject(
                "/api/v1/services/" + probeService + "/report?window=P30D", String.class))
                .as("report after unauthenticated attempts").contains("UNOBSERVED");
    }

    /** Bypasses the client's URI normalisation by writing the request line itself. */
    private String rawRequestStatus(String requestLine, String body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            String head = requestLine + " HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int eol = response.indexOf("\r\n");
            return eol < 0 ? response.strip() : response.substring(0, eol);
        }
    }

    // --- helpers -----------------------------------------------------------

    private ResponseEntity<String> post(String path, String json, String token) {
        return http.exchange(path, HttpMethod.POST,
                new HttpEntity<>(json, jsonHeaders(token)), String.class);
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.set(IngestAuthFilter.HEADER, token);
        }
        return h;
    }

    private static String deploymentJson() {
        return deploymentJson("auth-" + UUID.randomUUID());
    }

    private static String deploymentJson(String service) {
        Instant t = Instant.now().minus(1, ChronoUnit.HOURS);
        return """
                {"id":"%s","service":"%s","environment":"production","deployedAt":"%s",
                 "outcome":"SUCCESS","changes":[{"commitSha":"aaa","authoredAt":"%s"}]}
                """.formatted(UUID.randomUUID(), service, t, t.minus(2, ChronoUnit.HOURS));
    }

    private static String incidentJson() {
        Instant t = Instant.now().minus(2, ChronoUnit.HOURS);
        return """
                {"id":"%s","service":"auth-%s","causedByCommitSha":null,"detectedAt":"%s","resolvedAt":null}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), t);
    }
}
