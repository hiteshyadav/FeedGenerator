package org.feed;

import java.io.IOException;
import java.io.Reader;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Streams sentences; punctuation separates words and . ! ? terminate sentences. */
public final class SentenceParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(SentenceParser.class);

    private static final Set<String> ABBREVIATIONS = Set.of("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st");
    public static final int MAX_SENTENCE_CHARACTERS = 100_000;
    private final Reader input;
    private long number;
    private int pending = -1;
    public SentenceParser(Reader input) { this.input = Objects.requireNonNull(input); }

    /**
     * Reads the next nonempty sentence, ending at '.', '!', '?' or end of input (EOF).
     * A period after a recognized abbreviation such as "Dr" is retained in the word
     * and does not end the sentence. Whitespace, including Enter/newlines, separates
     * words but does not end a sentence; other punctuation generally separates words too.
     * Internal apostrophes are retained, with curly apostrophes normalized to straight ones.
     *
     * <p>Reading may block while waiting for more input. Words already read remain in
     * the local lists until a sentence terminator or EOF arrives. At EOF, remaining
     * words are returned as the final sentence; a later call returns an empty map.
     * Empty sentences are skipped. Each returned sentence receives a sequential number,
     * and its words are sorted case-insensitively, with reverse natural order breaking ties.
     *
     * @return an immutable map containing one sentence and its words, or an empty map
     *         at EOF when no words remain
     * @throws IOException if reading the input fails
     * @throws IllegalArgumentException if the sentence exceeds the character limit
     */
    public Map<Sentence, List<String>> next() throws IOException {
        var words = new ArrayList<String>();
        var word = new StringBuilder();
        int characters = 0;
        boolean apostrophe = false;
        // Keep reading until a nonempty sentence or EOF causes a return below.
        while (true) {
            // This call may wait for input; the loop does not poll while the user is idle.
            int c = readCodePoint();
            if (c == '.' && !apostrophe && ABBREVIATIONS.contains(word.toString().toLowerCase(Locale.ROOT))) {
                if (++characters > MAX_SENTENCE_CHARACTERS)
                    throw new IllegalArgumentException("Sentence exceeds %d characters".formatted(MAX_SENTENCE_CHARACTERS));
                word.append('.');
                finishWord(word, words);
                continue;
            }
            if (c == -1 || c == '.' || c == '!' || c == '?') {
                // Save the unfinished word, including when EOF ends an unpunctuated sentence.
                finishWord(word, words);
                if (!words.isEmpty()) {
                    words.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.reverseOrder()));
                    return Map.of(new Sentence(++number), List.copyOf(words));
                }
                if (c == -1) return Map.of();
                characters = 0;
                apostrophe = false;
            } else {
                characters += Character.charCount(c);
                if (characters > MAX_SENTENCE_CHARACTERS)
                    throw new IllegalArgumentException("Sentence exceeds %d characters".formatted(MAX_SENTENCE_CHARACTERS));
                if (Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK
                        || Character.getType(c) == Character.COMBINING_SPACING_MARK
                        || Character.getType(c) == Character.ENCLOSING_MARK) {
                    if (apostrophe) word.append('\'');
                    word.appendCodePoint(c);
                    apostrophe = false;
                } else if ((c == '\'' || c == '\u2019') && !word.isEmpty() && !apostrophe) {
                    // Retain only internal apostrophes; normalize curly apostrophes.
                    apostrophe = true;
                } else {
                    finishWord(word, words);
                    apostrophe = false;
                }
            }
        }
    }
    /**
     * Reads one Unicode code point from the Reader, which supplies UTF-16 code units.
     * Most characters use one code unit; a valid high/low surrogate pair is combined
     * into one code point for characters outside the Basic Multilingual Plane.
     *
     * <p>If a high surrogate is followed by a code unit that is not a low surrogate,
     * the high surrogate is returned alone and the second unit is saved in {@code pending}
     * for the next call, so no input is lost. Reads may block until input or EOF arrives.
     *
     * @return the next code point (or an unpaired surrogate code unit), or -1 at EOF
     * @throws IOException if reading the input fails
     */
    private int readCodePoint() throws IOException {
        // Consume any code unit saved by the previous call before reading the stream.
        int first = pending;
        pending = -1;
        if (first == -1) first = input.read();
        if (first == -1 || !Character.isHighSurrogate((char) first)) return first;
        // A high surrogate needs one more code unit to check for a complete pair.
        int second = input.read();
        if (second != -1 && Character.isLowSurrogate((char) second))
            return Character.toCodePoint((char) first, (char) second);
        pending = second;
        return first;
    }
    /* Each word will be added in List of words. List of word represent each sentence */
    private static void finishWord(StringBuilder word, List<String> words) {
        if (!word.isEmpty()) {
            //LOGGER.debug("Word: {}", word);
            words.add(word.toString());
            //LOGGER.debug("Words: {}", words);
            word.setLength(0);
        }
    }
}
