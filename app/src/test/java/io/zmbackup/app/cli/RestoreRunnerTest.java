package io.zmbackup.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.core.domain.RestoreResult;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestoreRunnerTest {

    @Test
    void printResultReturnsZeroAndSummarizesACompleteSuccess() {
        StringWriter buffer = new StringWriter();
        RestoreResult result = new RestoreResult(3, List.of());

        int exitCode = RestoreRunner.printResult(new PrintWriter(buffer), "session-1", result);

        assertEquals(0, exitCode);
        String output = buffer.toString();
        assertTrue(output.contains("Restore session session-1 completed (3/3 accounts restored)"), output);
        assertTrue(output.startsWith("Restore session session-1 completed ("), output);
    }

    @Test
    void printResultReturnsOneAndReportsFailedCountWhenSomeAccountsFail() {
        StringWriter buffer = new StringWriter();
        RestoreResult result = new RestoreResult(3, List.of("alice@example.com", "bob@example.com"));

        int exitCode = RestoreRunner.printResult(new PrintWriter(buffer), "session-2", result);

        assertEquals(1, exitCode);
        String output = buffer.toString();
        assertTrue(
                output.contains(
                        "Restore session session-2 completed with errors (1/3 accounts restored, 2 failed)"),
                output);
    }
}
