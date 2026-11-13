package com.jawad.qa.unit;

import com.jawad.qa.kpi.QaKpiReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The KPI numbers in the README come from this tool, so the tool itself is tested. */
@Tag("unit")
class QaKpiReportTest {
    @TempDir
    Path directory;

    @Test
    void nearestRankPercentile() {
        List<Long> hundred = LongStream.rangeClosed(1, 100).boxed().toList();
        assertEquals(95, QaKpiReport.percentile(hundred, 95));
        assertEquals(50, QaKpiReport.percentile(hundred, 50));
        assertEquals(7, QaKpiReport.percentile(List.of(7L), 99));
        assertEquals(0, QaKpiReport.percentile(List.of(), 95));
    }

    @Test
    void classifiesSurefireOutcomesByLayer() throws Exception {
        Path report = directory.resolve("TEST-sample.xml");
        Files.writeString(report, """
            <testsuite name="s">
              <testcase classname="com.jawad.qa.api.A" name="passes" time="0.5"/>
              <testcase classname="com.jawad.qa.api.A" name="fails" time="0.25"><failure message="x"/></testcase>
              <testcase classname="com.jawad.qa.api.A" name="flaky" time="1"><flakyFailure message="x"/></testcase>
              <testcase classname="com.jawad.qa.db.B" name="skipped" time="0"><skipped/></testcase>
              <testcase classname="Seat booking" name="scenario" time="0.1"/>
            </testsuite>""");
        var layers = QaKpiReport.readSurefire(report);
        assertEquals(new QaKpiReport.LayerCounts(2, 1, 0, 1, 1.75), layers.get("api"));
        assertEquals(new QaKpiReport.LayerCounts(0, 0, 1, 0, 0), layers.get("db"));
        assertEquals(1, layers.get("bdd").passed());
    }

    @Test
    void readsJmeterCsvWithQuotedLabels() throws Exception {
        Path csv = Files.createDirectories(directory.resolve("jmeter/results")).resolve("run.csv");
        Files.writeString(csv, """
            timeStamp,elapsed,label,responseCode,success
            1000,10,"GET /a, list",200,true
            1500,30,POST /b,201,true
            2000,20,POST /b,500,false
            """);
        var performance = QaKpiReport.readJmeter(List.of(csv));
        assertEquals(3, performance.samples());
        assertEquals(1, performance.errors());
        assertEquals(30, performance.p95());
        assertEquals(10, performance.p95ByLabel().get("GET /a, list"));
        assertEquals(3 / 1.02, performance.throughputPerSecond(), 1e-9);
    }

    @Test
    void defectLeakageIsEscapedOverTotal() throws Exception {
        Path ledger = directory.resolve("defects.csv");
        Files.writeString(ledger, "id,found_in\nBUG-1,pre-release\nBUG-2,production\nBUG-3,pre-release\nBUG-4,pre-release\n");
        assertEquals(0.25, QaKpiReport.defectLeakage(ledger));
        Files.writeString(ledger, "id,found_in\n");
        assertNull(QaKpiReport.defectLeakage(ledger));
    }
}
