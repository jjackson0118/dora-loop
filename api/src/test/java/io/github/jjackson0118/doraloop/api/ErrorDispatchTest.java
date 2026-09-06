package io.github.jjackson0118.doraloop.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The token check applies on ERROR dispatches too.
 *
 * <p>{@code OncePerRequestFilter.shouldNotFilterErrorDispatch()} returns
 * {@code true} by default, while Spring Boot registers a
 * {@code OncePerRequestFilter} for <em>every</em> dispatcher type. The
 * combination is quiet and easy to miss: the container invokes the filter on an
 * error dispatch and the filter declines to check, so a request reaches a
 * handler unauthenticated with the filter present, registered and running.
 *
 * <p>{@code TRACE} is the way in. Tomcat answers it 405 at the connector before
 * any filter runs, then error-dispatches to {@code server.error.path} carrying
 * the original method -- a method the filter classifies as a write -- and no
 * token. Nothing was writable through this: the dispatch target is
 * {@code /error}, not a path the caller picks, and {@code BasicErrorController}
 * writes nothing. It is the gap between the filter's claim and its behaviour
 * that is worth closing, because the claim is what the next person will rely on.
 *
 * <p>Raw sockets, because no HTTP client here will send a bare {@code TRACE}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dora.ingest.token=error-dispatch-secret")
@Testcontainers
class ErrorDispatchTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String TOKEN = "error-dispatch-secret";

    @LocalServerPort int port;

    @Test
    void anErrorDispatchOfAWriteMethodIsStillChecked() throws Exception {
        assertThat(status("TRACE /api/v1/deployments", null))
                .as("TRACE with no token: the error dispatch must not pass unchecked")
                .contains(" 403");
    }

    /**
     * The paired assertion.
     *
     * <p>Without it, a filter that refused every error dispatch outright would
     * satisfy the test above -- and would also break every error page in the
     * service, including the ones a legitimate authenticated client needs to
     * read when its own request was malformed.
     */
    @Test
    void anErrorDispatchWithTheTokenIsNotRefused() throws Exception {
        assertThat(status("TRACE /api/v1/deployments", TOKEN))
                .as("TRACE with the token: refused by the connector as 405, not by the filter")
                .contains(" 405");
    }

    /**
     * An error dispatch on a read is untouched.
     *
     * <p>A 404 must stay a 404. If the filter's error-dispatch handling ever
     * turns ordinary not-found responses into 403s, every client debugging a
     * wrong URL is told it has an authentication problem instead.
     */
    @Test
    void anErrorDispatchOfAReadIsUnaffected() throws Exception {
        assertThat(status("GET /no/such/path", null))
                .as("GET of a missing path, with no token")
                .contains(" 404");
    }

    private String status(String requestLine, String token) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            StringBuilder head = new StringBuilder(requestLine).append(" HTTP/1.1\r\n")
                    .append("Host: localhost\r\n")
                    .append("Content-Length: 0\r\n");
            if (token != null) {
                head.append(IngestAuthFilter.HEADER).append(": ").append(token).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int eol = response.indexOf("\r\n");
            String line = eol < 0 ? response.strip() : response.substring(0, eol);
            System.out.println("ERRDISPATCH " + requestLine
                    + (token == null ? " [no token]" : " [token]") + " -> " + line);
            return line;
        }
    }
}
