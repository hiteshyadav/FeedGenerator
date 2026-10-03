# FeedGenerator (Java 21+)

Logging uses SLF4J with Logback, configured in `src/main/resources/logback.xml`. 
The default level is `INFO`, and logs go to stderr. 
Override it with `-Dlog.level=DEBUG` before `-jar`, for example `java -Xmx32m -Dlog.level=DEBUG -jar target/FeedGenerator-1.0-SNAPSHOT.jar xml`. 
Supported levels are `OFF`, `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE`, and `ALL` (case-insensitive). 
TRACE includes DEBUG messages. Use `-Dlogback.configurationFile=path/to/logback.xml` for a custom configuration. 

Set `JAVA_HOME` to a JDK 21 or newer installation. 
Build and test with Maven: `mvn clean verify`. 
The executable JAR bundles its runtime logging dependencies; the existing `java -jar` commands still work.

Use standard input and output (commands below are for cmd.exe):

```
mvn clean install
java -Xmx32m -jar target/FeedGenerator-1.0-SNAPSHOT.jar xml < large.in > result.xml
java -Xmx32m -jar target/FeedGenerator-1.0-SNAPSHOT.jar csv < large.in > result.csv

java -Xmx32m -jar target\FeedGenerator-1.0-SNAPSHOT.jar xml < "C:\Users\Jadyu\Downloads\sample-files\small.in" > result.xml
java -Xmx32m -jar target\FeedGenerator-1.0-SNAPSHOT.jar xml < "C:\Users\Jadyu\Downloads\sample-files\small.in"
java -Xmx32m -jar target\FeedGenerator-1.0-SNAPSHOT.jar xml 
```

In PowerShell, run those redirections through `cmd /c` to stream bytes without PowerShell pipeline buffering.

To enter text interactively in Windows Command Prompt, run:

```
java -Xmx32m -jar target/FeedGenerator-1.0-SNAPSHOT.jar csv
java -Xmx32m -jar target/FeedGenerator-1.0-SNAPSHOT.jar xml
```

Type your sentences and press Enter. 
When finished, press **Ctrl+Z on a new, empty line**, then **Enter** to signal end of input. 
The CSV output will then appear. Pressing Enter alone only adds a newline; it does not finish input. 
In a POSIX terminal, use Ctrl+D to signal end of input.

CSV output waits until all input has been read because its header must include columns for the longest sentence. 
Rows are stored in a temporary file while reading, keeping memory bounded even with `-Xmx32m`.

Input and output are UTF-8. `.`, `!`, and `?` end sentences; repeated delimiters and empty sentences are ignored. 
Other punctuation and whitespace separate words. 
Words contain letters, numbers, combining marks, and supplementary Unicode letters and numbers. 
Internal apostrophes remain part of words; curly apostrophes are normalized to straight apostrophes. 
Surrounding quotes are ignored. Hyphens separate words. 
Title abbreviations Mr., Mrs., Ms., Dr., Prof., Sr., Jr., and St. keep their period without ending the sentence. 
Other abbreviations and decimal numbers receive no special handling. 
An unfinished final sentence is included. Duplicate words are retained. 
Sorting is case-insensitive with a case-sensitive tie-breaker that places lowercase before uppercase; 
it is independent of the machine locale.

`Sentence` is an immutable map key identified by its position. 
The parser returns a map containing one sentence at a time so identical sentences remain distinct without collecting the whole dataset. 
XML is emitted directly. CSV rows are spooled to a temporary UTF-8 file so the first row can contain the maximum word count across all sentences. 
Rows have variable lengths, matching the supplied example. 
Temporary files are deleted on success and on failure; sufficient temporary disk space is needed for CSV output.

Memory is bounded by the current sentence and fixed IO buffers. 
A sentence may contain at most 100,000 UTF-16 code units (including whitespace); longer sentences fail with a clear error instead of exhausting the heap. 
No total input-size limit applies. Errors go to stderr with exit code 1; incorrect arguments use exit code 2. 
An IO failure can leave partial XML output, so check the exit code.

Tests verify both supplied examples, exact outputs, empty input, Unicode, duplicates, final sentences without delimiters, map identity, IO failures, the sentence limit, and generated input larger than a 32 MB heap. 
To run the tests under that heap: `mvn -DargLine=-Xmx32m test`.
Test report will be available `target/reports/surefire.html`.
```
mvn -DargLine=-Xmx32m test

mvn clean test
mvn org.apache.maven.plugins:maven-surefire-report-plugin:3.5.2:report-only
```


To measure code coverage, run `mvn clean verify` using JDK 21 or newer, then open `target/site/jacoco/index.html` in your browser. 
JaCoCo reports line and branch coverage with links to highlighted source code. 
For coverage with a 32 MB test heap, run `mvn -DargLine=-Xmx32m clean verify`. 
The XML report for other tools is `target/site/jacoco/jacoco.xml`. 
The `test` phase collects coverage; the `verify` phase generates the report.
```
mvn -DargLine=-Xmx32m clean verify
```


Tests use JUnit tags: `fast` covers examples, parser edge cases, failures, and CLI behavior; 
`slow` covers `large.in` verification and the generated 64 MB input. 
Run `mvn -Dgroups=fast test` for fast tests, or `mvn -Dgroups=slow -DargLine=-Xmx32m test` for slow tests. 
`mvn test` runs both categories. 
Use `verify` instead of `test` to generate a coverage report; 
running just one category reports coverage for that subset (use `clean verify` to discard coverage from earlier runs).
```
mvn test
mvn "-Dgroups=fast" "-Dlog.level=DEBUG" test
mvn -Dgroups=slow -DargLine=-Xmx32m test
mvn "-Dgroups=fast"  test
mvn "-Dgroups=slow"  test
```

