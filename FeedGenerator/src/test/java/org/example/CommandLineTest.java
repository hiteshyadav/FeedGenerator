package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fast")
class CommandLineTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandLineTest.class);

    @TempDir Path directory;

    private record Result(int exitCode, String output, String error) {}

    private Result run(String input, String... arguments) throws Exception {
        return runWithLevel(null, input, arguments);
    }

    private Result runWithLevel(String level, String input, String... arguments) throws Exception {
        Path source = directory.resolve("input.txt");
        Path output = directory.resolve("output.txt");
        Path error = directory.resolve("error.txt");
        Files.writeString(source, input, StandardCharsets.UTF_8);
        var command = new ArrayList<String>();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.add("-Xmx32m");
        if (level != null) command.add("-Dlog.level=" + level);
        // Collect coverage from the real CLI, including paths that call System.exit.
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
                .forEach(command::add);
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add("org.example.Main");
        command.addAll(java.util.List.of(arguments));

        LOGGER.debug(" Given command : {} " ,command );
        LOGGER.debug(" Source path   : {} " ,source );

        Process process = new ProcessBuilder(command)
                .redirectInput(source.toFile()).redirectOutput(output.toFile())
                .redirectError(error.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI should terminate after EOF");
            return new Result(process.exitValue(), Files.readString(output), Files.readString(error));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test void xmlAcceptsMixedCaseAndPreservesUtf8() throws Exception {
        Result result = run("zebra café.", "XmL");
        assertEquals(0, result.exitCode());
        assertEquals("", result.error());
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<text>\n"
                + "    <sentence>\n        <word>café</word>\n        <word>zebra</word>\n"
                + "    </sentence>\n</text>\n", result.output());
    }

    @Test void commandLineLevelControlsErrorLogging() throws Exception {
        Result enabled = runWithLevel("info", "", "json");
        assertEquals(2, enabled.exitCode());
        assertTrue(enabled.error().contains("Unsupported format: json"));
        assertEquals("", enabled.output());
        Result disabled = runWithLevel("OFF", "", "json");
        assertEquals(2, disabled.exitCode());
        assertEquals("", disabled.error());
        assertEquals("", disabled.output());
    }

    @Test void csvFlushesCompleteOutputAfterEof() throws Exception {
        Result result = run("zebra apple. final", "csv");
        assertEquals(0, result.exitCode());
        assertEquals("", result.error());
        assertEquals(", Word 1, Word 2\nSentence 1, apple, zebra\nSentence 2, final\n", result.output());
    }

    @Test void invalidFormatFailsBeforeReadingInput() throws Exception {
        Result result = run("x".repeat(SentenceParser.MAX_SENTENCE_CHARACTERS + 1), "json");
        assertEquals(2, result.exitCode());
        assertEquals("", result.output());
        assertTrue(result.error().contains("Unsupported format: json. Expected xml or csv."));
    }

    @Test void missingAndExtraArgumentsPrintUsage() throws Exception {
        for (String[] arguments : new String[][] { {}, {"xml", "csv"} }) {
            Result result = run("hello.", arguments);
            assertEquals(2, result.exitCode());
            assertEquals("", result.output());
            assertTrue(result.error().contains("Usage:"));
        }
    }

    @Test void conversionFailureReturnsNonzeroExitAndNoCsv() throws Exception {
        Result result = run("x".repeat(SentenceParser.MAX_SENTENCE_CHARACTERS + 1), "csv");
        assertEquals(1, result.exitCode());
        assertEquals("", result.output());
        assertTrue(result.error().contains("Conversion failed: Sentence exceeds"));
    }
}
