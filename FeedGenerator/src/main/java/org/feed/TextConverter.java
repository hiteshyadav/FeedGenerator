package org.feed;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TextConverter {
    private static final Logger LOGGER = LoggerFactory.getLogger(TextConverter.class);
    public enum Format { XML, CSV }

    /** Converts UTF-8 standard input to standard output without loading the whole input. */
    public static void main(String[] args) {
        try {
            validateLogLevel();
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid log.level. Expected OFF, ERROR, WARN, INFO, DEBUG, TRACE, or ALL.");
            System.exit(2);
            return;
        }
        if (args.length != 1) {
            LOGGER.error("Usage: java -jar target/FeedGenerator-1.0-SNAPSHOT.jar <xml|csv>");
            System.exit(2);
            return;
        }
        final Format format;
        try {
            format = Format.valueOf(args[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.error("Unsupported format: {}. Expected xml or csv.", args[0]);
            System.exit(2);
            return;
        }
        try {
            var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            var output = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
            new TextConverter().convert(input, output, format);
            output.flush();
            LOGGER.debug("Conversion completed in {} format", format);
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.error("Conversion failed: {}", e.getMessage());
            System.exit(1);
        }
    }

    private static void validateLogLevel() {
        String configuredLevel = System.getProperty("log.level");
        if (configuredLevel == null) return;
        if (!Set.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL")
                .contains(configuredLevel.toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("Invalid log.level");
    }

    /** Caller owns the streams. Total dataset size does not determine memory usage. */
    public void convert(Reader input, Writer output, Format format) throws IOException {
        Objects.requireNonNull(format);
        var parser = new SentenceParser(input);
        if (format == Format.XML) writeXml(parser, output);
        else writeCsv(parser, output);
    }
    /**
     * Writes an XML document with one {@code <sentence>} element per parsed sentence
     * and one {@code <word>} element per word, in the order supplied by the parser.
     * Processes one sentence at a time without collecting the entire input.
     * The caller is responsible for flushing and closing the output.
     *
     * @param parser supplies a single-sentence map, or an empty map at end of input
     * @param output receives the XML document
     * @throws IOException if reading input or writing output fails
     */
    private void writeXml(SentenceParser parser, Writer output) throws IOException {
        // Start the document and open the root element containing all sentences.
        output.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<text>\n");
        Map<Sentence, List<String>> sentence;
        // Before each iteration, next() reads a sentence and assigns its map to sentence.
        // isEmpty() checks that map; ! means the loop runs only when it is NOT empty.
        // An empty map signals end of input, so the loop stops without writing a sentence.
        while (!(sentence = parser.next()).isEmpty()) {
            output.write("    <sentence>\n");

            LOGGER.debug("Writing XML sentence {}", sentence.values());

            // The map has one entry; its value is the current sentence's list of words.
            for (String word : sentence.values().iterator().next()) {
                output.write("        <word>");
                // Escape XML text characters, replacing & first to avoid escaping new entities.
                output.write(word.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
                output.write("</word>\n");
            }
            output.write("    </sentence>\n");
        }
        // Close the root element after all sentences have been written (also for empty input).
        output.write("</text>\n");
    }
    /**
     * Writes a CSV header followed by one row per sentence. Each row starts with
     * "Sentence N", followed by the words in the order supplied by the parser.
     * The header starts with an empty cell and has "Word 1" through "Word N"
     * columns, where N is the largest word count across all sentences.
     * Shorter rows are not padded with empty cells.
     *
     * <p>Because the header size is known only after all input has been read, rows
     * are first stored in a temporary UTF-8 file instead of being kept in memory.
     * Once EOF is reached, the header and stored rows are written to the output.
     * Reading may wait for more input; no CSV is written to the output before EOF.
     * Empty input produces only a newline. The temporary file is deleted in the
     * finally block, including when conversion fails.
     * The caller is responsible for flushing and closing the output.
     *
     * @param parser supplies a single-sentence map, or an empty map at end of input
     * @param output receives the CSV header and rows
     * @throws IOException if reading input, writing output, or managing the temporary file fails
     */
    private void writeCsv(SentenceParser parser, Writer output) throws IOException {
        // Store rows on disk until the longest sentence determines the header width.
        Path spool = Files.createTempFile("text-converter-", ".csv");
        try {
            int maximumWords = 0;
            try (Writer rows = Files.newBufferedWriter(spool, StandardCharsets.UTF_8)) {
                Map<Sentence, List<String>> sentence;
                // Read and assign the next sentence; an empty map at EOF stops the loop.
                // parser.next() may block while waiting for a sentence ending or EOF.
                while (!(sentence = parser.next()).isEmpty()) {
                    var entry = sentence.entrySet().iterator().next();
                    // Keep the largest word count seen so far for the header columns.
                    maximumWords = Math.max(maximumWords, entry.getValue().size());
                    rows.write("Sentence " + entry.getKey().number());

                    LOGGER.debug("Writing CSV sentence {}", entry.getKey().number());

                    for (String word : entry.getValue()) {
                        rows.write(", ");
                        // Quote fields containing CSV special characters and escape quotes.
                        rows.write(csvField(word));
                    }
                    rows.write("\n");
                }
            }
            // The temporary writer is now closed, so all rows are saved before copying.
            // Leave the first header cell empty above the "Sentence N" row labels.
            for (int i = 1; i <= maximumWords; i++) output.write(", Word " + i);
            output.write("\n");
            // Copy the saved rows after the header without loading the whole file into memory.
            try (Reader rows = Files.newBufferedReader(spool, StandardCharsets.UTF_8)) { rows.transferTo(output); }
        // Always attempt cleanup, even if parsing or writing throws an exception.
        } finally { Files.deleteIfExists(spool); }
    }
    private String csvField(String word) {
        if (word.indexOf(',') >= 0 || word.indexOf('"') >= 0 || word.indexOf('\n') >= 0 || word.indexOf('\r') >= 0)
            return "\"" + word.replace("\"", "\"\"") + "\"";
        return word;
    }
}
