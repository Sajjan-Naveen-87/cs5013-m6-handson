# Session 6A: RAG over a local Java repo (notes)

**Setup.** OpenJDK 21, langchain4j 0.36.0 (`make langchain4j`).
- **Embedder:** `MiniLmEmbedder` (all-MiniLM-L6-v2, 384-dim, in-process ONNX).
- **LLM:** `OllamaLlm` with **llama3.2:3b** (the 8 GB-RAM model from the handout), temperature 0, run locally by Ollama 0.40.4 on a Quadro P4000 GPU.
- **Retrieval:** top-k = 8.

Raw outputs of every run are in `runs/`.

## What was implemented

- **`RepoIndexer.chunkFile`** is a brace-balanced chunker. It ignores braces inside strings, char literals and comments, and emits **one chunk per method or constructor**. Each chunk:
  - starts with `// File: <path>`,
  - carries the enclosing class signature on a `// In: <class signature>` line,
  - keeps the method's leading JavaDoc and annotations.

  Each type also gets one *header* chunk with its JavaDoc, signature, fields/constants and abstract methods, so questions like "what is the cap" or "how is it indexed" have something to match. `-Dchunker=fixed50` switches to the bad 50-line chunker for task 7. The default is the method chunker.
- **`RagAssistant.retrieve`** embeds the query, ranks every chunk by cosine similarity and returns the top k. It **fails loudly if the query embedder's `modelId()` differs from the model the index was built with** (the handout's second pitfall).
- **`RagAssistant.buildPrompt`** loads `prompts/rag.txt` and fills in `{query}` and `{context}`. The context is `[i] source:start-end` followed by the chunk content. It uses `String.replace`, not a regex, so `$` in code cannot break it.
- **`Main`** has 10 domain questions plus 1 out-of-corpus question. It uses MiniLM + Ollama and scores retrieval automatically: each question has an expected file plus a code marker, and the run prints HIT/MISS and the hit-rate.
- `OllamaLlm` is copied from `example/`, with the model set to `llama3.2:3b`, a 3-minute timeout and `numPredict(400)`. Without the timeout, the unguarded run in task 8 hit langchain4j's default 60 s limit.

## Indexer stats (task 3)

`make run`, run twice each:

| Chunker | Chunks | Index time (run 1 / run 2) |
|---|---|---|
| Stub (one chunk per file), StubEmbedder | **8** | 36 ms / 36 ms |
| Method-level, MiniLM | **51** (9 type headers + 42 methods) | 723 ms / 724 ms |

There are 8 `.java` files, so 8 → 51 means the chunker splits on methods (about 5–6 per file) rather than merging them. The time increase is mostly MiniLM: 51 real embeddings instead of 8 hash vectors.

## Ten questions, answers, retrieval (tasks 5 and 6)

Final prompt, method chunker, top-8 (`runs/1-method-chunker.txt`). The **right chunk** column says whether the expected chunk was in the top-8, and at what rank (from `runs/3-retrieval-eval.txt`).

| # | Question | Right chunk in top-8? (rank) | LLM answer (short) | My judgement |
|---|---|---|---|---|
| 1 | Where is the fine for an overdue loan computed? | HIT (6), `FinePolicy.java:27-36` | `StandardFinePolicy.computeFine`, an implementation of `FinePolicy` | Correct |
| 2 | What class holds the catalogue of books, and how is it indexed? | HIT (1), `Catalog` header | `Catalog`, indexed by ISBN; `searchByTitle` is a substring scan | Correct |
| 3 | How does the system decide a loan has been returned? | HIT (1), `Loan.isOverdue` / `markReturned` | Checks whether `returnedOn` is non-null | Correct but incomplete: doesn't mention that `markReturned(day)` sets it |
| 4 | What is the maximum fine the policy allows? | HIT (3), `FinePolicy` header / `CAP` | 500 rupees | Correct |
| 5 | Which methods write to the loan registry? | HIT (2), `LoanRegistry.record` | `record(Loan)` and `loansFor(String)` | Partly wrong: `loansFor` only reads. It also misses `LibraryService.borrow`, which calls `registry.record` |
| 6 | How many books can a member have on loan at the same time? | HIT (2), `Member.addLoan` | 5; `addLoan` throws `IllegalStateException` at the cap | Correct |
| 7 | How many days is the default loan term? | HIT (2), `Loan` header | 21 days (`DEFAULT_TERM_DAYS`) | Correct |
| 8 | How does searching for a book by title work? | HIT (1), `Catalog.searchByTitle` | Case-insensitive substring match over titles in the `byIsbn` map | Correct |
| 9 | When are two Book objects considered equal? | HIT (1), `Book.equals` | Equal iff their ISBNs are equal | Correct |
| 10 | How does the report count loans per genre? | HIT (1), `ReportService.loansByGenre` | "in `loansByGenre()`" | Correct but shallow: doesn't explain the `merge(g, 1, Integer::sum)` loop |

**Retrieval hit-rate @8 = 10/10 = 1.00.** Stricter cut-offs: hit@1 = 5/10, hit@3 = 9/10, MRR = 0.70.

**Answers judged correct: 7/10 fully correct, 2 incomplete (Q3, Q10), 1 partly wrong (Q5).** Every error is a generation error, not a retrieval error: the right code was in the context each time.

### Prompt tuning note

My first, strictest guard said:

> If the context does not contain the answer, reply exactly: I do not find that in the passages.

With that guard, the 3B model **refused 5 of 10 answerable questions** (Q1, Q3, Q5, Q6, Q8), even though the right chunk was retrieved each time. Ollama's log showed no truncation (prompts of roughly 600–900 tokens against a 4096 window). The prompt and output are in `runs/rag-v1-too-strict.txt` and `runs/0-method-chunker-prompt-v1.txt`.

Changing the refusal trigger to "if the context is *unrelated* to the question", and telling the model to read method bodies, constants and JavaDoc, fixed all the false refusals. The out-of-corpus question was still refused. Small models over-apply a strict "reply exactly" instruction.

## Bad-chunker experiment (task 7)

Every 50 lines instead of method boundaries (`make run JAVA_OPTS=-Dchunker=fixed50`, output in `runs/2-fixed50-chunker.txt`):

| | Method-level | Fixed 50 lines |
|---|---|---|
| Chunks | 51 | 12 |
| hit@8 (the task's metric) | 10/10 | 10/10 (**change: 0**) |
| hit@1 / hit@3 | 5/10 / 9/10 | 7/10 / 9/10 |
| MRR | 0.70 | 0.83 |
| Share of whole index sent per query (top-8) | 16 % | **67 %** |
| Average prompt size at top-8 | 3,448 chars | **9,398 chars (2.7×)** |
| Answer latency, first measured run | 2.1 s average | 18.2 s average |
| Answers | 7 correct / 3 partial | 8 correct / 1 partial / **Q6 refused** |

The hit-rate fraction **did not drop** on this corpus, and I'm reporting that as measured. The repo is tiny: 8 files of 37–66 lines each. So "every 50 lines" is almost "one chunk per file", and each file is a single cohesive class. With only 12 chunks, top-8 returns two-thirds of the repo, so the right code is almost always in there.

The cost shows up elsewhere:
- **Prompts are 2.7× larger and answers were much slower** (18.2 s vs 2.1 s on the first measurement; the final re-run measured 7.1 s vs 6.6 s, because timings on this machine varied between runs).
- **Q6 regressed to a refusal.** The `Member.addLoan` cap check was buried in a 50-line blob.
- On a larger repo, the right code could never fit in top-8 this way.

Fixed-size chunks also cut methods in half. For example, `Book.java` lines 1–50 / 51–66 splits `equals()`.

This matches the handout's warning: a bad chunker only "works" here because k is large relative to the corpus, which is the same trick as cranking k up. A good top-3 (9/10 for the method chunker) is the better target.

## Question not in the corpus (task 8)

Q11: *"How does the system send email reminders to members using Spring's JavaMailSender?"* The repo has no email code and no Spring.

- **With the anti-hallucination lines** in `prompts/rag.txt`, the answer was **"I do not find that in the passages."** (`runs/1-method-chunker.txt`). The retriever still returned 8 loosely related `Member` / `Loan` chunks, as it always will. Only the instruction stops the model from using them.
- **Without the two instruction lines** (`runs/4-no-guard.txt`), the model **invented a full answer** in 92.5 s and hit the 400-token cap:
  - `spring.mail` properties and a `JavaMailSender` `@Bean`,
  - a made-up `ReminderService` that `@Autowired` a non-existent `MemberRepository`,
  - real repo names (`ReportService.overdue`, `Member`) woven in to make it look grounded.

  It even sent mail to `member.getName()`. The first attempt without the guard ran past langchain4j's default 60 s timeout, which is why `OllamaLlm` now sets a 3-minute timeout and an output cap.

  The no-guard run also made Q5 worse: it claimed `loansFor` "adds a new loan to the history list".
- **Instructions restored:** `prompts/rag.txt` is back to the final guarded version (identical to `runs/rag-final.txt`).

## How to reproduce

Start the model server:

```
ollama serve &
ollama pull llama3.2:3b
```

Then build and run from this folder:

```
make langchain4j && make test
make run                              # method chunker (default)
make run JAVA_OPTS=-Dchunker=fixed50  # task 7
javac -d build -cp "build:libs/langchain4j/*" runs/RetrievalEval.java
java -cp "build:libs/langchain4j/*" RetrievalEval   # hit@1/3/8, MRR
```

## Appendix: source code

### `src/RepoIndexer.java`

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Walks a directory of .java files, splits each file into method-level
 * chunks, embeds each chunk, and stores the result in an in-memory index.
 *
 * Session 6A: complete {@link #chunkFile(Path)} and {@link #embed(String)}.
 *
 * Chunking is a brace-balanced splitter: it tracks brace depth (ignoring
 * braces inside strings, char literals and comments), records the
 * signature of each type declared at depth 0, and cuts one chunk per
 * method/constructor body found at depth 1. Each type also gets a
 * "header" chunk (JavaDoc, signature, fields, abstract methods) so
 * questions about state/constants have something to hit.
 *
 * Task (7) "bad chunker": run with {@code -Dchunker=fixed50} to split
 * every 50 lines instead. The default is the method-level chunker.
 */
public class RepoIndexer {

    /** Lines per chunk for the deliberately bad fixed-size chunker. */
    static final int FIXED_LINES = 50;

    private static final Pattern TYPE_DECL = Pattern.compile(
            "\\b(class|interface|enum|record)\\s+\\w+");

    private final Embedder embedder;
    private final List<Chunk> index = new ArrayList<>();
    private final boolean fixedLineChunker;

    public RepoIndexer(Embedder embedder) {
        this(embedder, "fixed50".equals(System.getProperty("chunker")));
    }

    public RepoIndexer(Embedder embedder, boolean fixedLineChunker) {
        this.embedder = embedder;
        this.fixedLineChunker = fixedLineChunker;
    }

    /** Walks dir, indexing every *.java file below it. */
    public void indexDirectory(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                 .sorted()
                 .forEach(this::indexFile);
        }
    }

    private void indexFile(Path file) {
        try {
            List<Chunk> chunks = fixedLineChunker ? chunkFixedLines(file, FIXED_LINES) : chunkFile(file);
            for (Chunk c : chunks) {
                float[] v = embed(c.content());
                index.add(c.withEmbedding(v));
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to index " + file, e);
        }
    }

    /**
     * Split the file into method-level chunks. Each chunk's text is
     * prefixed with the file path and the enclosing class signature, and
     * keeps any leading JavaDoc / annotations of the method.
     * Falls back to the whole file as a single chunk if no type is found.
     */
    protected List<Chunk> chunkFile(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        int n = lines.size();
        int[] depthAtStart = braceDepths(lines);

        List<Chunk> out = new ArrayList<>();
        String typeSig = null;   // signature of the type we are inside
        int typeStart = -1;      // 0-based first line of the type (incl. JavaDoc)
        List<Integer> headerLines = new ArrayList<>();
        int docStart = -1;       // 0-based start of the most recent pending JavaDoc

        for (int i = 0; i < n; i++) {
            String t = lines.get(i).trim();
            int d = depthAtStart[i];

            if (d == 0) {
                if (t.startsWith("/**")) {
                    docStart = i;
                } else if (typeSig == null && !t.startsWith("*") && !t.startsWith("//")
                        && TYPE_DECL.matcher(stripComment(t)).find()) {
                    typeSig = signatureOf(lines, i);
                    typeStart = docStart >= 0 ? docStart : i;
                    headerLines.clear();
                    for (int j = typeStart; j <= i; j++) headerLines.add(j);
                    docStart = -1;
                } else if (!t.isEmpty() && !t.startsWith("*") && !t.startsWith("@")) {
                    docStart = -1;
                }
                continue;
            }

            if (d == 1 && typeSig != null) {
                if (t.equals("}")) {          // end of the type
                    headerLines.add(i);
                    out.add(0, headerChunk(file, lines, typeSig, headerLines));
                    typeSig = null;
                    continue;
                }
                if (t.startsWith("/**")) {
                    docStart = i;
                    continue;
                }
                if (t.startsWith("@") || (docStart >= 0 && (t.startsWith("*") || t.isEmpty()))) {
                    continue;                  // annotation, or inside / right after JavaDoc
                }
                int end = memberEnd(lines, depthAtStart, i);
                if (end > i || (isMethodHeader(lines, i) && end >= i && lines.get(end).contains("}"))) {
                    // Method / constructor with a body: lines [start, end].
                    int start = docStart >= 0 ? docStart : annotationStart(lines, i);
                    out.add(methodChunk(file, lines, typeSig, start, end));
                    docStart = -1;
                    i = end;
                    continue;
                }
                // Field, constant, abstract method: belongs to the header chunk.
                if (docStart >= 0) {
                    for (int j = docStart; j < i; j++) headerLines.add(j);
                    docStart = -1;
                }
                if (!t.isEmpty()) headerLines.add(i);
            }
        }

        if (out.isEmpty()) {
            String body = String.join("\n", lines);
            return List.of(new Chunk(file.toString(), 1, n, "// File: " + file + "\n" + body, null));
        }
        out.sort((a, b) -> a.startLine() != b.startLine()
                ? Integer.compare(a.startLine(), b.startLine())
                : Integer.compare(a.endLine(), b.endLine()));
        return out;
    }

    /** Task (7): the deliberately bad chunker -- a new chunk every {@code size} lines. */
    protected List<Chunk> chunkFixedLines(Path file, int size) throws IOException {
        List<String> lines = Files.readAllLines(file);
        List<Chunk> out = new ArrayList<>();
        for (int s = 0; s < lines.size(); s += size) {
            int e = Math.min(lines.size(), s + size);
            String text = "// File: " + file + "\n" + String.join("\n", lines.subList(s, e));
            out.add(new Chunk(file.toString(), s + 1, e, text, null));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Chunk methodChunk(Path file, List<String> lines, String typeSig, int start, int end) {
        StringBuilder sb = new StringBuilder();
        sb.append("// File: ").append(file).append('\n');
        sb.append("// In: ").append(typeSig).append('\n');
        for (int j = start; j <= end; j++) sb.append(lines.get(j)).append('\n');
        return new Chunk(file.toString(), start + 1, end + 1, sb.toString(), null);
    }

    private static Chunk headerChunk(Path file, List<String> lines, String typeSig, List<Integer> idx) {
        StringBuilder sb = new StringBuilder();
        sb.append("// File: ").append(file).append('\n');
        sb.append("// Type: ").append(typeSig).append(" (declaration, JavaDoc and fields)\n");
        for (int j : idx) sb.append(lines.get(j)).append('\n');
        return new Chunk(file.toString(), idx.get(0) + 1, idx.get(idx.size() - 1) + 1, sb.toString(), null);
    }

    /** Signature of a type declaration: text up to the opening brace (may span lines). */
    private static String signatureOf(List<String> lines, int i) {
        StringBuilder sb = new StringBuilder();
        for (int j = i; j < lines.size(); j++) {
            String l = lines.get(j).trim();
            int b = l.indexOf('{');
            sb.append(b >= 0 ? l.substring(0, b) : l).append(' ');
            if (b >= 0) break;
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    /** True if line i looks like a method/constructor signature (has '(' and no '='/';' before it). */
    private static boolean isMethodHeader(List<String> lines, int i) {
        String t = stripComment(lines.get(i).trim());
        int p = t.indexOf('(');
        if (p < 0) return false;
        String before = t.substring(0, p);
        return !before.contains("=") && !before.contains(";") && !TYPE_DECL.matcher(before).find();
    }

    /**
     * If the member starting at line i has a body (an '{' reached before a
     * ';' at depth 1) and is a method/constructor, return its last line
     * (where depth returns to 1); otherwise return i - 1.
     */
    private static int memberEnd(List<String> lines, int[] depthAtStart, int i) {
        if (!isMethodHeader(lines, i)) return i - 1;
        // Find the line where the body opens.
        int j = i;
        while (j < lines.size()) {
            String t = stripComment(lines.get(j));
            int brace = t.indexOf('{');
            int semi = t.indexOf(';');
            if (semi >= 0 && (brace < 0 || semi < brace)) return i - 1;   // abstract / interface method
            if (brace >= 0) break;
            j++;
        }
        // Walk forward until a later line starts back at depth 1 (body closed).
        for (int k = j + 1; k < lines.size(); k++) {
            if (depthAtStart[k] <= 1) return k - 1;
        }
        return lines.size() - 1;
    }

    /** Pull the start back over annotation lines directly above a method. */
    private static int annotationStart(List<String> lines, int i) {
        int s = i;
        while (s > 0 && lines.get(s - 1).trim().startsWith("@")) s--;
        return s;
    }

    private static String stripComment(String s) {
        int c = s.indexOf("//");
        return c >= 0 ? s.substring(0, c) : s;
    }

    /**
     * Brace depth at the start of every line, ignoring braces that appear
     * inside string/char literals and line/block comments.
     */
    static int[] braceDepths(List<String> lines) {
        int[] out = new int[lines.size()];
        int depth = 0;
        boolean inBlock = false;
        for (int li = 0; li < lines.size(); li++) {
            out[li] = depth;
            String l = lines.get(li);
            for (int c = 0; c < l.length(); c++) {
                char ch = l.charAt(c);
                char nx = c + 1 < l.length() ? l.charAt(c + 1) : '\0';
                if (inBlock) {
                    if (ch == '*' && nx == '/') { inBlock = false; c++; }
                    continue;
                }
                if (ch == '/' && nx == '/') break;
                if (ch == '/' && nx == '*') { inBlock = true; c++; continue; }
                if (ch == '"' || ch == '\'') {
                    char q = ch;
                    for (c++; c < l.length() && l.charAt(c) != q; c++) {
                        if (l.charAt(c) == '\\') c++;
                    }
                    continue;
                }
                if (ch == '{') depth++;
                else if (ch == '}') depth--;
            }
        }
        return out;
    }

    /**
     * Embedding goes straight through the {@link Embedder} interface; the
     * real model is chosen at construction time (StubEmbedder for tests,
     * MiniLmEmbedder for the real pipeline in Main).
     */
    protected float[] embed(String text) {
        return embedder.embed(text);
    }

    /** Embedding model the index was built with (checked at query time). */
    public String embedderModelId() {
        return embedder.modelId();
    }

    public List<Chunk> getIndex() {
        return index;
    }

    public int size() {
        return index.size();
    }
}
```

### `src/RagAssistant.java`

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Query-time RAG assistant. Takes a natural-language query, embeds it,
 * pulls the top-k nearest chunks from the {@link RepoIndexer}'s index,
 * formats them into the RAG prompt template, and calls the LLM.
 *
 * Session 6A: complete {@link #retrieve(String, int)} and
 * {@link #buildPrompt(String, List)}.  Do NOT edit {@link #answer}.
 */
public class RagAssistant {

    private final RepoIndexer indexer;
    private final Embedder embedder;
    private final LlmClient llm;
    private final Path promptTemplate;

    public RagAssistant(RepoIndexer indexer, Embedder embedder, LlmClient llm, Path promptTemplate) {
        this.indexer = indexer;
        this.embedder = embedder;
        this.llm = llm;
        this.promptTemplate = promptTemplate;
    }

    public String answer(String query, int k) throws IOException {
        List<Chunk> hits = retrieve(query, k);
        String prompt = buildPrompt(query, hits);
        return llm.complete(prompt);
    }

    /**
     * Embed the query, rank every indexed chunk by cosine similarity,
     * and return the top k (best first).
     *
     * Fails loudly if the query embedder is not the model the index was
     * built with -- mixing models makes the scores meaningless.
     */
    protected List<Chunk> retrieve(String query, int k) {
        if (!embedder.modelId().equals(indexer.embedderModelId())) {
            throw new IllegalStateException("embedding model mismatch: index built with "
                    + indexer.embedderModelId() + ", query embedded with " + embedder.modelId());
        }
        float[] q = embedder.embed(query);
        List<Chunk> ranked = new ArrayList<>(indexer.getIndex());
        ranked.sort(Comparator.comparingDouble((Chunk c) -> cosine(q, c.embedding())).reversed());
        return new ArrayList<>(ranked.subList(0, Math.min(k, ranked.size())));
    }

    /** Cosine similarity; does not assume the vectors are normalised. */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("dim mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * Load the template from {@link #promptTemplate}, substitute
     * {@code {query}} and {@code {context}} with the query and the hits
     * (one "source:start-end" header + content block per hit), and
     * return the assembled prompt.
     */
    protected String buildPrompt(String query, List<Chunk> hits) throws IOException {
        String template = Files.readString(promptTemplate);
        StringBuilder ctx = new StringBuilder();
        int n = 1;
        for (Chunk c : hits) {
            ctx.append("[").append(n++).append("] ")
               .append(c.source()).append(':').append(c.startLine()).append('-').append(c.endLine())
               .append('\n')
               .append(c.content().strip())
               .append("\n\n");
        }
        // Plain replace (not replaceAll) so '$' or '\' in code cannot break substitution.
        return template.replace("{query}", query).replace("{context}", ctx.toString().strip());
    }
}
```
