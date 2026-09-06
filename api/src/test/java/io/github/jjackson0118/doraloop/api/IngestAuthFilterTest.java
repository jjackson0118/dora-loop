package io.github.jjackson0118.doraloop.api;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The filter with <em>no token configured</em>, which is the case no
 * integration test can reach.
 *
 * <p>Every {@code @SpringBootTest} here sets {@code dora.ingest.token}, because
 * they need to write. So the branch that decides what an <em>unconfigured</em>
 * service does was covered by nothing, and a mutation making it open instead of
 * closed survived the whole suite. That branch is the one that matters most: it
 * is what runs when somebody deploys this in a hurry and forgets the
 * environment variable, and "no token set" defaulting to "no authentication"
 * is a control that reads as coverage without being one.
 *
 * <p>Driven directly rather than over HTTP, so it needs no container and no
 * second application context -- the thing under test is one constructor
 * argument and one branch.
 */
class IngestAuthFilterTest {

    private static MockHttpServletRequest write() {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/deployments");
        r.setRequestURI("/api/v1/deployments");
        return r;
    }

    @Test
    void withNoTokenConfiguredAWriteIsRefusedRatherThanOpen() throws Exception {
        for (String unset : new String[]{null, "", "   "}) {
            IngestAuthFilter filter = new IngestAuthFilter(unset);
            MockHttpServletRequest request = write();
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("token %s: an unconfigured service must refuse writes, not accept them",
                            unset == null ? "null" : "\"" + unset + "\"")
                    .isEqualTo(503);
            assertThat(response.getContentAsString()).contains("not configured");
            verify(chain, never()).doFilter(request, response);
        }
    }

    @Test
    void withNoTokenConfiguredAReadStillPasses() throws Exception {
        IngestAuthFilter filter = new IngestAuthFilter("");
        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/api/v1/services/x/report");
        read.setRequestURI("/api/v1/services/x/report");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(read, response, chain);

        assertThat(response.getStatus())
                .as("the report is deliberately readable without a token")
                .isEqualTo(200);
        verify(chain).doFilter(read, response);
    }

    /**
     * A write outside {@code /api/v1/} is this filter's business after all.
     *
     * <p>This test used to assert the opposite, and it was wrong in the way
     * that is hardest to see: it did not merely permit the gap, it documented
     * the gap as intended behaviour, so anyone who noticed and tried to close
     * it would have broken a test that looked deliberate.
     *
     * <p>The filter now keys on the method rather than the path, so no
     * agreement between its idea of a path and the dispatcher's is required.
     * The paths below are the ones that were reachable-in-principle:
     * {@code /actuator/*} write endpoints are one word of exposure
     * configuration away, and a bare {@code /} covers a servlet or forward that
     * never had a leading prefix at all.
     */
    @Test
    void aWriteOutsideTheApiPrefixIsRefusedToo() throws Exception {
        for (String path : new String[]{
                "/actuator/loggers/ROOT", "/actuator/env", "/actuator/shutdown", "/", "/anything"}) {
            IngestAuthFilter filter = new IngestAuthFilter("");
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setRequestURI(path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("POST %s on an unconfigured service", path)
                    .isEqualTo(503);
            verify(chain, never()).doFilter(request, response);
        }
    }

    /**
     * A method this code has never heard of is treated as a write.
     *
     * <p>The list the filter consults is the safe one, so the default for
     * anything unrecognised is to require the token. WebDAV verbs are the
     * concrete case -- {@code PROPPATCH} and {@code MKCOL} modify things -- but
     * the point is general: deciding an unknown method is harmless means
     * deciding without knowing what it does.
     */
    @Test
    void anUnknownMethodIsTreatedAsAWrite() throws Exception {
        for (String method : new String[]{
                "PUT", "PATCH", "DELETE", "PROPPATCH", "MKCOL", "FROBNICATE"}) {
            IngestAuthFilter filter = new IngestAuthFilter("");
            MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/deployments");
            request.setRequestURI("/api/v1/deployments");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).as("%s on an unconfigured service", method).isEqualTo(503);
            verify(chain, never()).doFilter(request, response);
        }
    }

    /**
     * The safe methods still pass, and the list is exactly those three.
     *
     * <p>The paired assertion matters: a filter that refuses everything would
     * satisfy every test above while taking the health probe down with it.
     */
    @Test
    void theSafeMethodsPassUnauthenticated() throws Exception {
        for (String method : new String[]{"GET", "HEAD", "OPTIONS"}) {
            IngestAuthFilter filter = new IngestAuthFilter("");
            MockHttpServletRequest request = new MockHttpServletRequest(method, "/actuator/health");
            request.setRequestURI("/actuator/health");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).as("%s must not be refused", method).isEqualTo(200);
            verify(chain).doFilter(request, response);
        }
    }
}
