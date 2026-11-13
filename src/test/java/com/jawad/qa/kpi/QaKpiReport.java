package com.jawad.qa.kpi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/**
 * Turns raw run output into QA KPIs: Surefire XML (tests per layer, pass, flaky and first-attempt rates),
 * JaCoCo CSV (coverage), JMeter CSV (p95, throughput, errors) and an optional defect ledger (leakage).
 * Writes kpis.json, kpis.prom (Prometheus text format, pushed to Grafana) and kpis.md (CI job summary).
 *
 * <p>Usage: {@code QaKpiReport <output-dir> <input-dir>...}. Every input directory is searched recursively,
 * which lets CI pass the downloaded artifacts of several jobs at once.
 */
public final class QaKpiReport {
    private static final String PACKAGE_ROOT = "com.jawad.qa.";

    public record LayerCounts(int passed, int failed, int skipped, int flaky, double seconds) {
        int executed() {
            return passed + failed;
        }

        LayerCounts plus(LayerCounts other) {
            return new LayerCounts(passed + other.passed, failed + other.failed, skipped + other.skipped,
                flaky + other.flaky, seconds + other.seconds);
        }
    }

    public record Performance(long samples, long errors, double throughputPerSecond, long p50, long p95, long p99,
                              Map<String, Long> p95ByLabel) {
    }

    private QaKpiReport() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: QaKpiReport <output-dir> <input-dir>...");
        }
        Path output = Path.of(args[0]);
        List<Path> inputs = Stream.of(args).skip(1).map(Path::of).filter(Files::isDirectory).toList();
        long p95BudgetMs = Long.getLong("kpi.p95BudgetMs", 250);

        Map<String, LayerCounts> layers = new TreeMap<>();
        Map<String, Double> coverage = new LinkedHashMap<>();
        List<Path> jmeterResults = new ArrayList<>();
        Path defects = Path.of("quality", "defects.csv");
        for (Path input : inputs) {
            try (Stream<Path> files = Files.walk(input)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (name.startsWith("TEST-") && name.endsWith(".xml")) {
                        readSurefire(file).forEach((layer, counts) -> layers.merge(layer, counts, LayerCounts::plus));
                    } else if (name.equals("jacoco.csv")) {
                        coverage.putAll(readJacoco(file));
                    } else if (name.endsWith(".csv") && file.toString().replace('\\', '/').contains("jmeter/results")) {
                        jmeterResults.add(file);
                    }
                }
            }
        }
        Performance performance = jmeterResults.isEmpty() ? null : readJmeter(jmeterResults);
        Double leakage = Files.exists(defects) ? defectLeakage(defects) : null;

        Map<String, Object> kpis = summarize(layers, coverage, performance, leakage, p95BudgetMs);
        Files.createDirectories(output);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .writeValue(output.resolve("kpis.json").toFile(), kpis);
        Files.writeString(output.resolve("kpis.prom"), prometheus(layers, coverage, performance, leakage));
        String markdown = markdown(layers, coverage, performance, leakage, p95BudgetMs);
        Files.writeString(output.resolve("kpis.md"), markdown);
        System.out.println(markdown);

        if (performance != null && performance.p95() > p95BudgetMs) {
            System.err.printf("p95 %d ms exceeds budget %d ms%n", performance.p95(), p95BudgetMs);
            System.exit(2);
        }
    }

    /** Layer is the package below com.jawad.qa; a test that passed only on re-run counts as passed and flaky. */
    public static Map<String, LayerCounts> readSurefire(Path file) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(file.toFile());
        var cases = document.getElementsByTagName("testcase");
        Map<String, LayerCounts> layers = new TreeMap<>();
        for (int i = 0; i < cases.getLength(); i++) {
            var testcase = (Element) cases.item(i);
            String layer = layerOf(testcase.getAttribute("classname"));
            boolean failed = testcase.getElementsByTagName("failure").getLength() > 0
                || testcase.getElementsByTagName("error").getLength() > 0;
            boolean skipped = testcase.getElementsByTagName("skipped").getLength() > 0;
            boolean flaky = !failed && (testcase.getElementsByTagName("flakyFailure").getLength() > 0
                || testcase.getElementsByTagName("flakyError").getLength() > 0);
            double seconds = parseSeconds(testcase.getAttribute("time"));
            var counts = new LayerCounts(!failed && !skipped ? 1 : 0, failed ? 1 : 0, skipped ? 1 : 0,
                flaky ? 1 : 0, seconds);
            layers.merge(layer, counts, LayerCounts::plus);
        }
        return layers;
    }

    static String layerOf(String className) {
        if (!className.startsWith(PACKAGE_ROOT)) {
            // Cucumber reports scenarios under the feature name rather than the Java package.
            return "bdd";
        }
        String rest = className.substring(PACKAGE_ROOT.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? "other" : rest.substring(0, dot);
    }

    private static double parseSeconds(String raw) {
        try {
            return raw.isBlank() ? 0 : Double.parseDouble(raw.replace(",", ""));
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }

    /** Returns covered ratios for LINE and BRANCH, summed over every class in the report. */
    public static Map<String, Double> readJacoco(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> header = List.of(lines.get(0).split(","));
        Map<String, Double> ratios = new LinkedHashMap<>();
        for (String counter : List.of("LINE", "BRANCH", "INSTRUCTION")) {
            int missed = header.indexOf(counter + "_MISSED");
            int covered = header.indexOf(counter + "_COVERED");
            long missedTotal = 0;
            long coveredTotal = 0;
            for (String line : lines.subList(1, lines.size())) {
                String[] cells = line.split(",");
                missedTotal += Long.parseLong(cells[missed]);
                coveredTotal += Long.parseLong(cells[covered]);
            }
            long total = missedTotal + coveredTotal;
            ratios.put(counter.toLowerCase(Locale.ROOT), total == 0 ? 0 : (double) coveredTotal / total);
        }
        return ratios;
    }

    /** Reads JMeter CSV results (timeStamp, elapsed, label, ..., success columns by header name). */
    public static Performance readJmeter(List<Path> files) throws IOException {
        List<Long> elapsed = new ArrayList<>();
        Map<String, List<Long>> byLabel = new TreeMap<>();
        long errors = 0;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                continue;
            }
            List<String> header = List.of(lines.get(0).split(","));
            int timeStamp = header.indexOf("timeStamp");
            int elapsedColumn = header.indexOf("elapsed");
            int label = header.indexOf("label");
            int success = header.indexOf("success");
            for (String line : lines.subList(1, lines.size())) {
                String[] cells = splitCsv(line);
                long started = Long.parseLong(cells[timeStamp]);
                long duration = Long.parseLong(cells[elapsedColumn]);
                elapsed.add(duration);
                byLabel.computeIfAbsent(cells[label], key -> new ArrayList<>()).add(duration);
                if (!Boolean.parseBoolean(cells[success])) {
                    errors++;
                }
                first = Math.min(first, started);
                last = Math.max(last, started + duration);
            }
        }
        double seconds = Math.max(0.001, (last - first) / 1000.0);
        Map<String, Long> p95ByLabel = new TreeMap<>();
        byLabel.forEach((name, values) -> p95ByLabel.put(name, percentile(values, 95)));
        return new Performance(elapsed.size(), errors, elapsed.size() / seconds, percentile(elapsed, 50),
            percentile(elapsed, 95), percentile(elapsed, 99), p95ByLabel);
    }

    /** Nearest-rank percentile, the same definition JMeter's HTML dashboard uses. */
    public static long percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    static String[] splitCsv(String line) {
        List<String> cells = new ArrayList<>();
        var current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells.toArray(String[]::new);
    }

    /** Ledger columns: id,found_in where found_in is "pre-release" or "production". */
    public static Double defectLeakage(Path ledger) throws IOException {
        List<String> rows = Files.readAllLines(ledger).stream().skip(1).filter(row -> !row.isBlank()).toList();
        if (rows.isEmpty()) {
            return null;
        }
        long escaped = rows.stream().filter(row -> row.strip().endsWith(",production")).count();
        return (double) escaped / rows.size();
    }

    private static LayerCounts total(Map<String, LayerCounts> layers) {
        return layers.values().stream().reduce(new LayerCounts(0, 0, 0, 0, 0), LayerCounts::plus);
    }

    private static Map<String, Object> summarize(Map<String, LayerCounts> layers, Map<String, Double> coverage,
                                                 Performance performance, Double leakage, long budget) {
        LayerCounts total = total(layers);
        Map<String, Object> kpis = new LinkedHashMap<>();
        Map<String, Object> perLayer = new LinkedHashMap<>();
        layers.forEach((layer, counts) -> perLayer.put(layer, Map.of(
            "tests", counts.executed() + counts.skipped(), "passed", counts.passed(), "failed", counts.failed(),
            "skipped", counts.skipped(), "flaky", counts.flaky(), "seconds", round(counts.seconds()),
            "share", total.executed() == 0 ? 0 : round((double) counts.executed() / total.executed()))));
        kpis.put("layers", perLayer);
        kpis.put("tests", total.executed() + total.skipped());
        kpis.put("passRate", rate(total.passed(), total.executed()));
        kpis.put("firstAttemptPassRate", rate(total.passed() - total.flaky(), total.executed()));
        kpis.put("flakyRate", rate(total.flaky(), total.executed()));
        kpis.put("testSeconds", round(total.seconds()));
        kpis.put("coverage", coverage);
        if (performance != null) {
            Map<String, Object> perf = new LinkedHashMap<>();
            perf.put("samples", performance.samples());
            perf.put("errorRate", rate(performance.errors(), performance.samples()));
            perf.put("throughputPerSecond", round(performance.throughputPerSecond()));
            perf.put("p50Ms", performance.p50());
            perf.put("p95Ms", performance.p95());
            perf.put("p99Ms", performance.p99());
            perf.put("p95BudgetMs", budget);
            perf.put("p95ByLabelMs", performance.p95ByLabel());
            kpis.put("performance", perf);
        }
        kpis.put("defectLeakage", leakage);
        return kpis;
    }

    private static double rate(long part, long whole) {
        return whole == 0 ? 0 : round((double) part / whole);
    }

    private static double round(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }

    static String prometheus(Map<String, LayerCounts> layers, Map<String, Double> coverage,
                             Performance performance, Double leakage) {
        var out = new StringBuilder();
        LayerCounts total = total(layers);
        out.append("# TYPE qa_tests gauge\n");
        layers.forEach((layer, c) -> {
            gauge(out, "qa_tests{layer=\"" + layer + "\",result=\"passed\"}", c.passed());
            gauge(out, "qa_tests{layer=\"" + layer + "\",result=\"failed\"}", c.failed());
            gauge(out, "qa_tests{layer=\"" + layer + "\",result=\"skipped\"}", c.skipped());
            gauge(out, "qa_tests{layer=\"" + layer + "\",result=\"flaky\"}", c.flaky());
        });
        out.append("# TYPE qa_layer_duration_seconds gauge\n");
        layers.forEach((layer, c) -> gauge(out, "qa_layer_duration_seconds{layer=\"" + layer + "\"}", c.seconds()));
        out.append("# TYPE qa_pass_rate gauge\n");
        gauge(out, "qa_pass_rate", rate(total.passed(), total.executed()));
        out.append("# TYPE qa_first_attempt_pass_rate gauge\n");
        gauge(out, "qa_first_attempt_pass_rate", rate(total.passed() - total.flaky(), total.executed()));
        out.append("# TYPE qa_flaky_rate gauge\n");
        gauge(out, "qa_flaky_rate", rate(total.flaky(), total.executed()));
        out.append("# TYPE qa_coverage_ratio gauge\n");
        coverage.forEach((counter, ratio) -> gauge(out, "qa_coverage_ratio{counter=\"" + counter + "\"}", ratio));
        if (performance != null) {
            out.append("# TYPE qa_perf_latency_ms gauge\n");
            gauge(out, "qa_perf_latency_ms{quantile=\"0.5\"}", performance.p50());
            gauge(out, "qa_perf_latency_ms{quantile=\"0.95\"}", performance.p95());
            gauge(out, "qa_perf_latency_ms{quantile=\"0.99\"}", performance.p99());
            out.append("# TYPE qa_perf_throughput_rps gauge\n");
            gauge(out, "qa_perf_throughput_rps", performance.throughputPerSecond());
            out.append("# TYPE qa_perf_error_ratio gauge\n");
            gauge(out, "qa_perf_error_ratio", rate(performance.errors(), performance.samples()));
        }
        if (leakage != null) {
            out.append("# TYPE qa_defect_leakage_ratio gauge\n");
            gauge(out, "qa_defect_leakage_ratio", leakage);
        }
        return out.toString();
    }

    private static void gauge(StringBuilder out, String series, double value) {
        out.append(series).append(' ').append(String.format(Locale.ROOT, "%.6f", value)).append('\n');
    }

    static String markdown(Map<String, LayerCounts> layers, Map<String, Double> coverage, Performance performance,
                           Double leakage, long budget) {
        LayerCounts total = total(layers);
        var out = new StringBuilder("## QA KPIs\n\n| Layer | Tests | Passed | Failed | Flaky | Skipped | Time (s) |\n");
        out.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        layers.forEach((layer, c) -> out.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %d | %.1f |%n",
            layer, c.executed() + c.skipped(), c.passed(), c.failed(), c.flaky(), c.skipped(), c.seconds())));
        out.append(String.format(Locale.ROOT, "| **total** | **%d** | %d | %d | %d | %d | %.1f |%n%n",
            total.executed() + total.skipped(), total.passed(), total.failed(), total.flaky(), total.skipped(),
            total.seconds()));
        out.append(String.format(Locale.ROOT, "- Pass rate: **%.1f%%** (first attempt %.1f%%), flaky rate %.1f%%%n",
            100 * rate(total.passed(), total.executed()), 100 * rate(total.passed() - total.flaky(), total.executed()),
            100 * rate(total.flaky(), total.executed())));
        if (!coverage.isEmpty()) {
            out.append(String.format(Locale.ROOT, "- Coverage of src/main: line **%.1f%%**, branch %.1f%%%n",
                100 * coverage.get("line"), 100 * coverage.get("branch")));
        }
        if (performance != null) {
            out.append(String.format(Locale.ROOT,
                "- JMeter: %,d samples, **%.0f req/s**, p50 %d ms, **p95 %d ms** (budget %d ms), p99 %d ms, errors %.2f%%%n",
                performance.samples(), performance.throughputPerSecond(), performance.p50(), performance.p95(), budget,
                performance.p99(), 100 * rate(performance.errors(), performance.samples())));
        }
        out.append(leakage == null ? "- Defect leakage: no defects recorded in quality/defects.csv\n"
            : String.format(Locale.ROOT, "- Defect leakage: %.1f%%%n", 100 * leakage));
        return out.toString();
    }
}
