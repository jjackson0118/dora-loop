package io.github.jjackson0118.doraloop.api;

import io.github.jjackson0118.doraloop.core.DoraCalculator;
import io.github.jjackson0118.doraloop.core.Thresholds;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
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
    /**
     * Read in one transaction, or the report contradicts itself.
     *
     * <p>The deployments and their changes are two queries. Without an
     * enclosing transaction each gets its own snapshot, so a deployment
     * committed between them appears with an empty changes list -- which is
     * indistinguishable from a legal redeploy, and therefore renders as a
     * deployment contributing no lead-time observation rather than as an error.
     * Measured under four concurrent writers: 348 of 385 reads had
     * deployment_frequency.observedN not equal to lead_time_for_changes.observedN,
     * skewed toward fewer changes every time. Silent under-observation in the
     * read path of a project about silent under-observation.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @GetMapping("/services/{service}/report")
    ReportDtos.ReportDto report(@PathVariable("service") String rawService,
                                @RequestParam(defaultValue = "P30D") String window) {
        // The path variable arrives percent-encoded, so it is decoded here.
        //
        // Measured before this: POST a deployment for a service named
        // "sp ace-..." and the row is written; GET the report for it returns
        // 200 with every metric UNOBSERVED and the service echoed back as
        // "sp%20ace-...". The lookup was for the encoded literal, which matches
        // nothing, so the answer was a clean report about a service that has
        // data. Every test used a UUID-based name that needs no encoding, so
        // nothing saw it.
        //
        // That is worse than a wrong number. The javadoc above defends
        // 200-with-UNOBSERVED as the honest answer when nothing was measured;
        // here the measurement exists and the endpoint says it does not, which
        // is the failure this project is entirely about, in its read path.
        //
        // Decoded by hand rather than with URLDecoder, which is a
        // form-decoder: it turns "+" into a space, so a service legitimately
        // named "pl+us" would silently become "pl us" -- swapping one wrong
        // lookup for another.
        String service = decodePathSegment(rawService);

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

    /** Percent-decoding only: {@code +} is a literal here, not a space. */
    private static String decodePathSegment(String raw) {
        if (raw.indexOf('%') < 0) {
            return raw;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) {
                    throw new IllegalArgumentException("truncated percent-escape in service name");
                }
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("invalid percent-escape in service name");
                }
                out.write((hi << 4) + lo);
                i += 2;
            } else {
                out.write(String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8), 0,
                        String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
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
