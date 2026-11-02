package com.commercefoundry.domainlayer.job.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JobResultContentTest {
    @Test
    void bytesAreDefensivelyCopiedAndLogsContainNoContent() {
        byte[] source = {1, 2};
        var file = new JobResultContent("report.csv", "text/csv", source, true);
        source[0] = 9;
        byte[] read = file.bytes();
        read[1] = 8;
        assertThat(file.bytes()).containsExactly(1, 2);
        assertThat(file.toString()).doesNotContain("report.csv", "text/csv");
        assertThat(new JobOutcome("private-json", file).toString()).doesNotContain("private-json");
        assertThat(new JobOutcome("{}").file()).isNull();
        assertThat(JobOutcome.empty().resultJson()).isNull();
    }

    @Test
    void pathsControlsAndEmptyContentAreRejected() {
        for (String name : new String[] {"../report.csv", "dir/report.csv", "dir\\report.csv", "report\n.csv", "..", "고객.csv"}) {
            assertThatThrownBy(() -> new JobResultContent(name, "text/csv", new byte[] {1}, false)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new JobResultContent("report.csv", "text/csv\r\nHeader:x", new byte[] {1}, false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobResultContent("report.csv", "text/csv", new byte[0], false)).isInstanceOf(IllegalArgumentException.class);
    }
}
