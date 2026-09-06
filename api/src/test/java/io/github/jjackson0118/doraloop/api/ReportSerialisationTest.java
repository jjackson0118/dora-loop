package io.github.jjackson0118.doraloop.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jjackson0118.doraloop.core.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an unobserved metric reaches the wire.
 *
 * <p>These are the assertions most likely to be broken by a configuration
 * change rather than by a code change -- enabling NON_NULL inclusion anywhere
 * would silently turn every unobserved metric into an omitted key, and an
 * omitted key deserializes to 0 in most typed clients. That is this project's
 * central failure, reintroduced by a serializer default.
 */
@JsonTest
class ReportSerialisationTest {

    @Autowired ObjectMapper mapper;

    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    private ReportDtos.ReportDto emptyReport() {
        return ReportDtos.ReportDto.from(
                new DoraCalculator(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(30))
                        .calculate("svc", List.of(), List.of()));
    }

    @Test
    @DisplayName("an unobserved metric serialises value as an explicit null, never omitted")
    void unobservedValueIsExplicitNull() throws Exception {
        String json = mapper.writeValueAsString(emptyReport());

        assertThat(json).contains("\"value\":null");
        assertThat(json).contains("\"state\":\"UNOBSERVED\"");
    }

    @Test
    @DisplayName("there is no boolean health field a three-state signal could be flattened into")
    void noBooleanOkField() throws Exception {
        String json = mapper.writeValueAsString(emptyReport());

        assertThat(json).doesNotContain("\"ok\":");
        assertThat(json).doesNotContain("\"healthy\":");
    }

    @Test
    @DisplayName("every metric carries its definition of wrong onto the wire")
    void definitionOfWrongIsServed() throws Exception {
        String json = mapper.writeValueAsString(emptyReport());

        assertThat(json).contains("definitionOfWrong");
        assertThat(emptyReport().metrics())
                .allSatisfy(m -> assertThat(m.definitionOfWrong()).isNotBlank());
    }

    @Test
    @DisplayName("DORA metrics and data-quality signals are served separately")
    void doraAndDataQualityAreSeparate() {
        ReportDtos.ReportDto r = emptyReport();

        assertThat(r.metrics()).hasSize(4);
        assertThat(r.dataQuality()).hasSize(3);
        assertThat(r.dataQuality()).allSatisfy(
                m -> assertThat(m.name()).startsWith("data_quality."));
        assertThat(r.summary().unobserved()).hasSize(7);
    }
}
