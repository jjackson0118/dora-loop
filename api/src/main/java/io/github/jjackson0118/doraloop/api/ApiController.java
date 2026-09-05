package io.github.jjackson0118.doraloop.api;

import io.github.jjackson0118.doraloop.core.DoraCalculator;
import io.github.jjackson0118.doraloop.core.Thresholds;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
class ApiController {

    private final IngestService ingest;
    private final EventRepository repo;
    private final Clock clock;
    private final Thresholds thresholds;

    ApiController(IngestService ingest, EventRepository repo, Clock clock, Thresholds thresholds) {
        this.ingest = ingest;
        this.repo = repo;
        this.clock = clock;
        this.thresholds = thresholds;
    }

    @PostMapping("/deployments")
    ResponseEntity<IngestDtos.IngestAccepted> deployments(@Valid @RequestBody IngestDtos.DeploymentDto dto) {
        return respond(ingest.accept(dto));
    }

    @PostMapping("/incidents")
    ResponseEntity<IngestDtos.IngestAccepted> incidents(@Valid @RequestBody IngestDtos.IncidentDto dto) {
        return respond(ingest.accept(dto));
    }

    /**
     * 201 only when something was created.
     *
     * <p>Every ingest used to answer 201 Created, including the branch that
     * found a duplicate and wrote nothing. 201 is a claim that a resource came
     * into existence, and a client that trusts it -- to count deployments it
     * successfully recorded, say -- is counting retries.
     */
    private static ResponseEntity<IngestDtos.IngestAccepted> respond(IngestDtos.IngestAccepted a) {
        HttpStatus status = a.disposition() == IngestDtos.Disposition.STORED
                ? HttpStatus.CREATED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(a);
    }

    /**
     * A service with no events returns 200 with every metric UNOBSERVED, not
     * 404. Under this project's thesis that is the honest answer: nothing was
     * measured, and UNOBSERVED says exactly that. A 404 would invite consumers
     * to treat "not measured" as a client error and skip it.
     */
    @GetMapping("/services/{service}/report")
    ReportDtos.ReportDto report(@PathVariable String service,
                                @RequestParam(defaultValue = "P30D") String window) {
        Duration w;
        try {
            w = Duration.parse(window);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("window must be an ISO-8601 duration, e.g. P30D");
        }
        if (w.isNegative() || w.isZero() || w.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("window must be positive and at most P365D");
        }
        return ReportDtos.ReportDto.from(
                new DoraCalculator(clock, w, thresholds)
                        .calculate(service, repo.deploymentsFor(service), repo.incidentsFor(service)));
    }

    // --- error shape -------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail malformed(MethodArgumentNotValidException e) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Malformed event");
        // Every violating field, not the first. A 400 naming one field per
        // round trip makes a pipeline author fix N times.
        p.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList());
        return p;
    }

    @ExceptionHandler(IngestService.ConflictingReplay.class)
    ProblemDetail conflict(IngestService.ConflictingReplay e) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Event id reused with a different payload");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Invalid request");
        p.setDetail(e.getMessage());
        return p;
    }
}
