package org.feed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import static org.junit.jupiter.api.Assertions.*;

class TextConverterTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(TextConverterTest.class);
    private static final Pattern REFERENCE_WORD = Pattern.compile("[\\p{L}\\p{N}\\p{M}]+(?:['’][\\p{L}\\p{N}\\p{M}]+)*");

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void logTestStart(TestInfo testInfo) {
        LOGGER.atInfo().log(() -> "Running test: " + testInfo.getDisplayName());
    }

    private static final String SAMPLE = "Mary had a little lamb. Peter called for the wolf, and Aesop came.\nCinderella likes shoes.";
    private static final String SPACED = "  Mary   had a little  lamb  . \n\n\n  Peter   called for the wolf   ,  and Aesop came .\n Cinderella  likes shoes..";
    private static final String CSV = """
            , Word 1, Word 2, Word 3, Word 4, Word 5, Word 6, Word 7, Word 8
            Sentence 1, a, had, lamb, little, Mary
            Sentence 2, Aesop, and, called, came, for, Peter, the, wolf
            Sentence 3, Cinderella, likes, shoes
            """;
    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <text>
                <sentence>
                    <word>a</word>
                    <word>had</word>
                    <word>lamb</word>
                    <word>little</word>
                    <word>Mary</word>
                </sentence>
                <sentence>
                    <word>Aesop</word>
                    <word>and</word>
                    <word>called</word>
                    <word>came</word>
                    <word>for</word>
                    <word>Peter</word>
                    <word>the</word>
                    <word>wolf</word>
                </sentence>
                <sentence>
                    <word>Cinderella</word>
                    <word>likes</word>
                    <word>shoes</word>
                </sentence>
            </text>
            """;
    private String convert(String input, TextConverter.Format format) throws IOException {
        var output = new StringWriter();
        new TextConverter().convert(new StringReader(input), output, format);
        return output.toString();
    }

    @Tag("slow") @ParameterizedTest(name = "Resource {0}: XML and CSV results")
    @ValueSource(strings = {"large.in"})
    void resourceFilesProduceExpectedResults(String resourceName) throws Exception {
        for (var format : TextConverter.Format.values()) {
            Path result = temporaryDirectory.resolve(resourceName + "." + format.name().toLowerCase(Locale.ROOT));
            LOGGER.atInfo().log(() -> "Converting resource " + resourceName + " to " + format);
            LOGGER.debug("Path: {}", result);
            try {
                try (Reader input = resourceReader(resourceName);
                     Writer output = Files.newBufferedWriter(result, StandardCharsets.UTF_8)) {
                    new TextConverter().convert(input, output, format);
                }
                // Use a separate regex-based oracle, rather than the production parser.
                try (Reader input = resourceReader(resourceName);
                     Scanner reference = new Scanner(input).useDelimiter("[.!?]+");
                     BufferedReader actual = Files.newBufferedReader(result, StandardCharsets.UTF_8)) {
                    if (format == TextConverter.Format.XML) verifyResourceXml(reference, actual);
                    else verifyResourceCsv(reference, actual);
                    if (reference.ioException() != null) throw reference.ioException();
                }
                LOGGER.atInfo().log(() -> "Verified " + resourceName + " in " + format + " format");
            } finally {
                Files.deleteIfExists(result);
            }
        }
    }

    @Tag("fast") @Test
    void smallInputMatchesExpectedCsv() throws Exception {
        var output = new StringWriter();
        try (Reader input = resourceReader("small.in")) {
            new TextConverter().convert(input, output, TextConverter.Format.CSV);
        }
        // Compare lines so Windows and Unix line endings are equivalent.
        try (var expected = new BufferedReader(resourceReader("small.csv"));
             var actual = new BufferedReader(new StringReader(output.toString()))) {

              LOGGER.debug(" expectedSentences : {}" + expected);
              LOGGER.debug(" actualSentences   : {}" + actual);

            int line = 0;
            String expectedLine;
            while ((expectedLine = expected.readLine()) != null) {
                assertEquals(expectedLine, actual.readLine(), "small.csv line " + ++line);
            }
            assertNull(actual.readLine(), "Unexpected extra CSV rows");
        }
        LOGGER.debug("Verified small.in against small.csv");
    }

    @Tag("fast") @Test
    void smallInputMatchesExpectedXml() throws Exception {
        var output = new StringWriter();
        try (Reader input = resourceReader("small.in")) {
            new TextConverter().convert(input, output, TextConverter.Format.XML);
        }
        // Compare XML content: indentation and entity spellings do not affect equality.
        try (Reader expected = resourceReader("small.xml")) {
            var expectedSentences = readXmlSentenceWords(expected);
            var actualSentences = readXmlSentenceWords(new StringReader(output.toString()));

            LOGGER.debug(" expectedSentences : {}" + expectedSentences);
            LOGGER.debug(" actualSentences : {}" + expectedSentences);

            for (int i = 0; i < Math.min(expectedSentences.size(), actualSentences.size()); i++) {
                assertEquals(expectedSentences.get(i), actualSentences.get(i), "small.xml sentence " + (i + 1));
            }
            assertEquals(expectedSentences.size(), actualSentences.size(), "XML sentence count");
        }
        LOGGER.debug("Verified small.in against small.xml");
    }

    private List<List<String>> readXmlSentenceWords(Reader reader) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(new InputSource(reader));
        assertEquals("text", document.getDocumentElement().getTagName());
        var sentences = document.getDocumentElement().getChildNodes();
        var result = new ArrayList<List<String>>();
        for (int i = 0; i < sentences.getLength(); i++) {
            var sentence = sentences.item(i);
            if (sentence.getNodeType() == org.w3c.dom.Node.TEXT_NODE && sentence.getTextContent().isBlank()) continue;
            assertEquals("sentence", sentence.getNodeName());
            var words = new ArrayList<String>();
            var children = sentence.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                var word = children.item(j);
                if (word.getNodeType() == org.w3c.dom.Node.TEXT_NODE && word.getTextContent().isBlank()) continue;
                assertEquals("word", word.getNodeName());
                words.add(word.getTextContent());
            }
            result.add(words);
        }
        return result;
    }

    private Reader resourceReader(String name) {
        InputStream stream = getClass().getResourceAsStream("/input/" + name);
        assertNotNull(stream, "Missing test resource: /input/" + name);
        return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    /** Independent sentence extraction, retaining only one expected sentence in memory. */
    private List<String> nextReferenceWords(Scanner reference) {
        while (reference.hasNext()) {
            var matcher = REFERENCE_WORD.matcher(reference.next());
            var words = new ArrayList<String>();
            while (matcher.find()) words.add(matcher.group().replace('\u2019', '\''));
            if (!words.isEmpty()) {
                words.sort(Comparator.comparing((String word) -> word.toLowerCase(Locale.ROOT))
                        .thenComparing(Comparator.reverseOrder()));
                return words;
            }
        }
        return null;
    }

    private void verifyResourceCsv(Scanner reference, BufferedReader actual) throws IOException {
        String header = actual.readLine();
        int maximumWords = 0;
        long number = 0;
        List<String> expected;
        while ((expected = nextReferenceWords(reference)) != null) {
            maximumWords = Math.max(maximumWords, expected.size());
            number++;
            assertEquals("Sentence " + number + ", " + String.join(", ", expected),
                    actual.readLine(), "CSV sentence " + number);
        }
        assertTrue(number > 0, "Resource must contain sentences");
        assertNull(actual.readLine(), "Unexpected extra CSV rows");
        var expectedHeader = new StringBuilder();
        for (int i = 1; i <= maximumWords; i++) expectedHeader.append(", Word ").append(i);
        assertEquals(expectedHeader.toString(), header, "CSV header must match longest sentence");
        LOGGER.debug("Verified " + number + " CSV sentences");
    }

    private void verifyResourceXml(Scanner reference, Reader actual) throws Exception {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        var xml = factory.createXMLStreamReader(actual);
        try {
            assertEquals("1.0", xml.getVersion());
            assertTrue(xml.isStandalone());
            assertEquals(XMLStreamConstants.START_ELEMENT, xml.nextTag());
            assertEquals("text", xml.getLocalName());
            long number = 0;
            List<String> expected;
            while ((expected = nextReferenceWords(reference)) != null) {
                number++;
                assertEquals(XMLStreamConstants.START_ELEMENT, xml.nextTag(), "XML sentence " + number);
                assertEquals("sentence", xml.getLocalName());
                var words = new ArrayList<String>();
                while (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
                    assertEquals("word", xml.getLocalName());
                    words.add(xml.getElementText());
                }
                assertEquals("sentence", xml.getLocalName());
                assertEquals(expected, words, "XML sentence " + number);
            }
            assertTrue(number > 0, "Resource must contain sentences");
            assertEquals(XMLStreamConstants.END_ELEMENT, xml.nextTag(), "Unexpected extra XML sentence");
            assertEquals("text", xml.getLocalName());
            while (xml.hasNext()) {
                int event = xml.next();
                assertTrue(event == XMLStreamConstants.END_DOCUMENT
                        || (event == XMLStreamConstants.CHARACTERS && xml.isWhiteSpace()),
                        "Unexpected content after XML root");
            }
            LOGGER.debug("Verified " + number + " XML sentences");
        } finally {
            xml.close();
        }
    }
    @Tag("fast") @Test void sampleCsvAndWhitespaceVariant() throws Exception {
        assertEquals(CSV, convert(SAMPLE, TextConverter.Format.CSV));
        assertEquals(CSV, convert(SPACED, TextConverter.Format.CSV));
    }

    @Tag("fast") @Test void contractionsRemainWholeAndSurroundingQuotesAreIgnored() throws Exception {
        assertEquals(", Word 1, Word 2, Word 3, Word 4\nSentence 1, couldn't, isn't, word, you'd\n",
                convert("'couldn't' isn't ‘you’d’ 'word'.", TextConverter.Format.CSV));
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<text>\n"
                        + "    <sentence>\n        <word>couldn't</word>\n    </sentence>\n</text>\n",
                convert("couldn't.", TextConverter.Format.XML));
    }

    @Tag("fast") @Test void titleAbbreviationDoesNotEndSentence() throws Exception {
        assertEquals(", Word 1, Word 2, Word 3\nSentence 1, called, Mr., Young\nSentence 2, Next\n",
                convert("Mr. Young called. Next.", TextConverter.Format.CSV));
    }

    @Tag("fast") @Test void lowercasePrecedesUppercaseForOtherwiseEqualWords() throws Exception {
        assertEquals(", Word 1, Word 2, Word 3, Word 4\nSentence 1, in, In, markets, Markets\n",
                convert("Markets markets In in.", TextConverter.Format.CSV));
    }
    @Tag("fast") @Test void sampleXmlAndWhitespaceVariant() throws Exception {
        LOGGER.debug(" Test start sampleXmlAndWhitespaceVariant ");
        String str=convert(SAMPLE, TextConverter.Format.XML);
        LOGGER.debug(" Str [{}] " + str);
        assertEquals(XML, str);
        assertEquals(XML, convert(SPACED, TextConverter.Format.XML));
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(XML)));
        assertEquals(3, document.getElementsByTagName("sentence").getLength());
    }
    @Tag("fast") @Test void emptyInputAndRepeatedDelimiters() throws Exception {
        assertEquals("\n", convert(" . ! ? , \n..", TextConverter.Format.CSV));
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<text>\n</text>\n",
                convert("", TextConverter.Format.XML));
    }
    @Tag("fast") @Test void unicodeDuplicatesAndUnterminatedSentence() throws Exception {
        assertEquals(", Word 1, Word 2, Word 3\nSentence 1, apple, apple, Zebra\nSentence 2, café, élève\n",
                convert("Zebra apple apple! élève café", TextConverter.Format.CSV));
    }
    @Tag("fast") @Test void sentenceIsAMapKeyAndDuplicateSentencesStayDistinct() throws Exception {
        var parser = new SentenceParser(new StringReader("same. same."));
        var sentences = new HashMap<Sentence, List<String>>();
        sentences.putAll(parser.next());
        sentences.putAll(parser.next());
        assertEquals(2, sentences.size());
        assertEquals(List.of("same"), sentences.get(new Sentence(1)));
        assertTrue(parser.next().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Sentence(0));
    }
    @Tag("fast") @Test void overlongSentenceFailsClearly() {
        assertThrows(IllegalArgumentException.class, () ->
                convert("x".repeat(SentenceParser.MAX_SENTENCE_CHARACTERS + 1), TextConverter.Format.CSV));
    }
    @Tag("fast") @Test void inputAndOutputFailuresPropagate() {
        Reader broken = new Reader() {
            public int read(char[] b, int off, int len) throws IOException { throw new IOException("read failed"); }
            public void close() {}
        };
        assertThrows(IOException.class, () -> new TextConverter().convert(broken, new StringWriter(), TextConverter.Format.CSV));
        Writer brokenOutput = new Writer() {
            public void write(char[] b, int off, int len) throws IOException { throw new IOException("write failed"); }
            public void flush() {}
            public void close() {}
        };
        for (var format : TextConverter.Format.values())
            assertThrows(IOException.class, () -> new TextConverter().convert(new StringReader(SAMPLE), brokenOutput, format));
    }
    @Tag("slow") @Test void streamsInputLargerThanHeap() throws Exception {
        // Generates 64 MB without storing the dataset or result.
        for (var format : TextConverter.Format.values()) {
            LOGGER.atInfo().log(() -> "Converting 64 MB generated input to " + format);
            new TextConverter().convert(new BufferedReader(generatedInput()), Writer.nullWriter(), format);
            LOGGER.atInfo().log(() -> "Completed 64 MB conversion to " + format);
        }
    }
    @Tag("fast") @Test void combiningMarksRemainPartOfWords() throws Exception {
        assertEquals(", Word 1, Word 2, Word 3\nSentence 1, a\u0301, b\u0903, c\u20DD\n",
                convert("a\u0301 b\u0903 c\u20DD.", TextConverter.Format.CSV));
    }

    @Tag("fast") @Test void unpairedSurrogatesSeparateWordsWithoutLosingNextCharacter() throws Exception {
        assertEquals(", Word 1, Word 2\nSentence 1, a, b\n",
                convert("a\uD800b\uDC00.", TextConverter.Format.CSV));
        assertEquals(", Word 1\nSentence 1, a\n",
                convert("a\uD800", TextConverter.Format.CSV));
    }

    @Tag("fast") @Test void trailingAndRepeatedApostrophesDoNotMergeWords() throws Exception {
        assertEquals(", Word 1, Word 2, Word 3\nSentence 1, a, b, mr\n",
                convert("a''b mr'.", TextConverter.Format.CSV));
    }

    @Tag("fast") @Test void abbreviationPeriodCountsTowardsSentenceLimit() {
        assertThrows(IllegalArgumentException.class, () ->
                convert(" ".repeat(SentenceParser.MAX_SENTENCE_CHARACTERS - 2) + "Mr.",
                        TextConverter.Format.CSV));
    }

    private Reader generatedInput() {
        return new Reader() {
            private long remaining = 64L * 1024 * 1024;
            private int index;
            private final String text = "Mary had a little lamb. ";
            public int read(char[] b, int off, int len) {
                if (remaining == 0) return -1;
                int n = (int) Math.min(len, remaining);
                for (int i = 0; i < n; i++) b[off + i] = text.charAt(index++ % text.length());
                remaining -= n;
                return n;
            }
            public void close() {}
        };
    }
    @Tag("fast") @Test void supplementaryLettersAndEmojiSeparators() throws Exception {
        assertEquals(", Word 1, Word 2\nSentence 1, a, \uD801\uDC00\n",
                convert("\uD801\uDC00\uD83D\uDE00a.", TextConverter.Format.CSV));
    }
}
