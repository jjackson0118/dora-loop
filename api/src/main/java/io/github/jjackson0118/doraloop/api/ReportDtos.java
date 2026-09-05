package io.github.jjackson0118.doraloop.api;

import io.github.jjackson0118.doraloop.core.DoraReport;
import io.github.jjackson0118.doraloop.core.Metric;

import java.time.Instant;
import java.util.List;

/**
 * The read contract.
 *
 * <p>Three rules govern how an unobserved metric renders, and all three exist
 * so a consumer cannot mistake "not measured" for zero:
 *
 * <ul>
 *   <li>{@code value} is {@code null} and <strong>explicitly present</strong>.
 *       Jackson's NON_NULL inclusion is deliberately not enabled -- an omitted
 *       key deserializes to 0 in most typed clients, which reintroduces the
 *       exact failure this project is about, via a serializer default.</li>
 *   <li>{@code state} is always emitted and is three-valued.</li>
 *   <li>There is no {@code ok} boolean. A boolean cannot carry three states,
 *       and whichever value it took for UNOBSERVED would conflate it with
 *       either healthy or degraded.</li>
 * </ul>
 */
final class ReportDtos {

    record MetricDto(
            String name,
            String state,
            Double value,
            String unit,
            int observedN,
            String definitionOfWrong
    ) {
        static MetricDto from(Metric m) {
            return new MetricDto(m.name(), m.state().name(), m.value(),
                    m.unit(), m.observedN(), m.definitionOfWrong());
        }
    }

    record SummaryDto(List<String> degraded, List<String> unobserved) {}

    record ReportDto(
            String service,
            Instant windowStart,
            Instant windowEnd,
            List<MetricDto> metrics,
            List<MetricDto> dataQuality,
            SummaryDto summary
    ) {
        static ReportDto from(DoraReport r) {
            List<MetricDto> dora = r.metrics().stream().map(MetricDto::from).toList();
            // The DORA metrics and the operational signals are kept apart, per
            // ADR 0003: the report is not polluted by data quality, and data
            // quality still participates in alerting.
            List<MetricDto> quality = r.allSignals().stream()
                    .filter(m -> !r.metrics().contains(m))
                    .map(MetricDto::from).toList();
            return new ReportDto(
                    r.service(), r.windowStart(), r.windowEnd(), dora, quality,
                    new SummaryDto(
                            r.alerting().stream().map(Metric::name).toList(),
                            r.unobserved().stream().map(Metric::name).toList()));
        }
    }

    private ReportDtos() {}
}
