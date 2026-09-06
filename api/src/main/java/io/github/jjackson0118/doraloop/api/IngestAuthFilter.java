package io.github.jjackson0118.doraloop.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * A shared secret on the write endpoints, and nothing on the read endpoint.
 *
 * <p>This service is meant to be published, and the report is the thing worth
 * showing. Ingest is not: an unauthenticated {@code POST /api/v1/deployments}
 * reachable from the internet means anyone can write this service's own
 * delivery metrics. The failure mode is not a defaced page, it is a metric that
 * is quietly wrong -- someone else's deployments counted as ours, or a flood of
 * synthetic successes burying a real change failure. For a project whose entire
 * argument is that a number nobody can trust is worse than no number, leaving
 * that open would be the loudest possible contradiction.
 *
 * <p><strong>It fails closed.</strong> With no token configured, ingest is
 * refused rather than opened. A token that defaults to "no authentication" is a
 * control that reads as coverage without being it, which is this project's
 * stated central failure -- and the default is what runs when someone deploys in
 * a hurry. The refusal says the service is misconfigured rather than pretending
 * the request was bad, because a producer sending correct events to an
 * unconfigured service has done nothing wrong.
 *
 * <p>The comparison is constant-time. A byte-by-byte early return leaks the
 * token one character at a time to anyone who can measure response latency, and
 * this endpoint is going on the public internet.
 */
@Component
class IngestAuthFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Dora-Ingest-Token";

    private final byte[] expected;

    IngestAuthFilter(@Value("${dora.ingest.token:}") String token) {
        this.expected = token == null || token.isBlank()
                ? null
                : token.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Anything that is not a read requires the token, at any path.
     *
     * <p>This deliberately does not look at the path. An earlier version
     * engaged only for {@code POST} under {@code /api/v1/}, which left the
     * service's safety resting on two different path normalizers agreeing:
     * this filter's {@code startsWith} and the dispatcher's pattern matching. A
     * raw-socket probe found where they disagree -- {@code //api/v1/deployments}
     * and {@code /API/V1/deployments} skip this filter entirely, and were
     * refused only because the dispatcher happened to answer 404. Nothing was
     * writable, but nothing was making it so on purpose.
     *
     * <p>The concrete hazard was one word of configuration away.
     * {@code /actuator/loggers/{name}} and {@code /actuator/shutdown} are write
     * endpoints; they answered 404 only because
     * {@code management.endpoints.web.exposure.include} lists
     * {@code health,info}. Adding {@code loggers} to that line -- the sort of
     * thing done while debugging a production incident -- would have published
     * an unauthenticated write endpoint, and no test here would have failed.
     * (An earlier version of this comment also named {@code /actuator/env};
     * that was wrong. {@code EnvironmentEndpoint} carries only a
     * {@code @ReadOperation}, so there is no {@code POST /actuator/env} in
     * Spring Boot to protect. The read endpoints are a separate problem with a
     * separate control -- see {@code endpoints.enabled-by-default} in
     * {@code application.yml} -- because this filter lets every GET through by
     * design and so can never be the answer to a disclosure.)
     *
     * <p>So the rule is the safe-method list, not an endpoint list: GET, HEAD
     * and OPTIONS pass, and everything else -- POST, PUT, PATCH, DELETE, and
     * any extension method this code has never heard of -- must present the
     * token. An unrecognised method is treated as a write, because the
     * alternative is deciding a method is safe without knowing what it does.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return method != null
                && ("GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method));
    }

    /**
     * Check ERROR dispatches too.
     *
     * <p>{@link OncePerRequestFilter#shouldNotFilterErrorDispatch()} defaults
     * to {@code true}, and Spring Boot registers a {@code OncePerRequestFilter}
     * for <em>every</em> dispatcher type -- so the container was calling this
     * filter on error dispatches and the filter was declining to look. Observed
     * rather than deduced: {@code TRACE /api/v1/deployments} is answered 405 by
     * the connector before any filter runs, Tomcat then error-dispatches to
     * {@code /error} carrying the original method and no token, and the request
     * reached a handler unauthenticated.
     *
     * <p>Nothing was writable through it -- the dispatch target is
     * {@code server.error.path}, not something a caller chooses, and
     * {@code BasicErrorController} writes nothing -- so this closes a gap
     * between the code and its own claim rather than a hole. The claim is
     * "anything that is not a read requires the token", and a filter that is
     * invoked and abstains does not meet it.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (expected == null) {
            problem(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "Ingest is not configured",
                    "This service has no ingest token set, so it refuses writes rather than "
                            + "accepting them unauthenticated. Set dora.ingest.token "
                            + "(DORA_INGEST_TOKEN) and restart.");
            return;
        }

        String presented = request.getHeader(HEADER);
        if (presented == null || !MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected)) {
            // 403, not 401. RFC 9110 requires a 401 to carry a
            // WWW-Authenticate challenge naming a scheme, and a shared-secret
            // header is not a scheme -- there is nothing to challenge with, and
            // nothing for a client to retry differently. 403 is "you may not do
            // this", which is exactly the situation.
            //
            // The JDK's HttpURLConnection is what surfaced this: on a 401 it
            // tries to re-authenticate and cannot resend a streamed body, so
            // the client failed before it could read the response. A client
            // quirk pointed at a real correctness question.
            problem(response, HttpStatus.FORBIDDEN,
                    "Ingest requires a token",
                    "Send the shared secret in the " + HEADER + " header.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Hand-written so the body matches the ProblemDetail shape the controller returns. */
    private static void problem(HttpServletResponse response, HttpStatus status,
                                String title, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status.value()
                        + ",\"detail\":\"" + detail + "\"}");
    }
}
