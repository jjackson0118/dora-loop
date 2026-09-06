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

    @Test
    void aWriteOutsideTheApiPrefixIsNotThisFiltersBusiness() throws Exception {
        IngestAuthFilter filter = new IngestAuthFilter("");
        MockHttpServletRequest actuator = new MockHttpServletRequest("POST", "/actuator/refresh");
        actuator.setRequestURI("/actuator/refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(actuator, response, chain);

        verify(chain).doFilter(actuator, response);
    }
}
