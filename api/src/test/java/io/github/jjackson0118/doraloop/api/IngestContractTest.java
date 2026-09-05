package io.github.jjackson0118.doraloop.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The wire contract for ingest, and the two properties easiest to lose. */
@JsonTest
class IngestContractTest {

    @Autowired ObjectMapper mapper;

    @Test
    @DisplayName("NEGATIVE CONTROL: an unknown field is refused, not silently dropped")
    void unknownFieldRefused() {
        // A producer still sending the pre-ADR-0002 'commitAuthoredAt' would
        // otherwise be accepted with an empty changes list -- a deployment
        // contributing no lead-time observations, which renders as fewer
        // observations rather than as an error.
        String stale = """
                {"id":"a","service":"s","environment":"production",
                 "deployedAt":"2026-09-05T12:00:00Z","outcome":"SUCCESS",
                 "commitAuthoredAt":"2026-09-01T12:00:00Z","changes":[]}""";

        assertThatThrownBy(() -> mapper.readValue(stale, IngestDtos.DeploymentDto.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    @DisplayName("a deployment carrying no changes is legal -- a redeploy is a real deployment")
    void emptyChangesAccepted() throws Exception {
        String redeploy = """
                {"id":"a","service":"s","environment":"production",
                 "deployedAt":"2026-09-05T12:00:00Z","outcome":"SUCCESS","changes":[]}""";

        IngestDtos.DeploymentDto dto = mapper.readValue(redeploy, IngestDtos.DeploymentDto.class);

        assertThat(dto.changes()).isEmpty();
    }

    @Test
    @DisplayName("a deployment carries every commit in the range, each with its own author date")
    void changesCarryPerCommitAuthorDates() throws Exception {
        String body = """
                {"id":"a","service":"s","environment":"production",
                 "deployedAt":"2026-09-05T12:00:00Z","outcome":"SUCCESS",
                 "changes":[{"commitSha":"aaa","authoredAt":"2026-09-01T09:00:00Z"},
                            {"commitSha":"bbb","authoredAt":"2026-08-20T09:00:00Z"}]}""";

        IngestDtos.DeploymentDto dto = mapper.readValue(body, IngestDtos.DeploymentDto.class);

        assertThat(dto.changes()).hasSize(2);
        assertThat(dto.changes().get(1).authoredAt()).isBefore(dto.changes().get(0).authoredAt());
    }
}
