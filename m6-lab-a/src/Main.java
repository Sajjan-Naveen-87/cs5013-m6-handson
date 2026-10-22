import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * End-to-end driver for Session 6A:
 *   1. index sample-repo/;
 *   2. for each question in {@link #QUESTIONS}, retrieve the top-k
 *      chunks and ask the LLM for an answer.
 *
 * Students add their own questions to {@link #QUESTIONS} (task 5 of
 * handson6.tex) and re-run with {@code make run}.
 *
 * Uses {@link MiniLmEmbedder} + {@link OllamaLlm} (llama3.2:3b), so it
 * needs {@code make langchain4j} and a running {@code ollama serve}.
 * Pass {@code JAVA_OPTS=-Dchunker=fixed50} to make run for task (7).
 */
public class Main {

    /**
     * Task (5): ten questions targeting the sample-repo/ library-management
     * domain (books, members, loans, fines), plus task (8): one question
     * whose answer is NOT in the corpus (always the last entry).
     */
    static final String[] QUESTIONS = {
        "Where is the fine for an overdue loan computed?",
        "What class holds the catalogue of books, and how is it indexed?",
        "How does the system decide a loan has been returned?",
        "What is the maximum fine the policy allows?",
        "Which methods write to the loan registry?",
        "How many books can a member have on loan at the same time?",
        "How many days is the default loan term?",
        "How does searching for a book by title work?",
        "When are two Book objects considered equal?",
        "How does the report count loans per genre?",
        // Task (8): not in the corpus.
        "How does the system send email reminders to members using Spring's JavaMailSender?",
    };

    /**
     * Task (6): the "right" chunk for each question, as {file, marker}.
     * A question is a retrieval HIT if any top-k chunk from that file
     * contains one of the markers. null = not in corpus (not scored).
     */
    static final String[][] EXPECTED = {
        {"FinePolicy.java",   "public BigDecimal computeFine(Loan loan, LocalDate today) {"},
        {"Catalog.java",      "private final Map<String, Book> byIsbn"},
        {"Loan.java",         "public void markReturned(LocalDate day)", "returnedOn != null"},
        {"FinePolicy.java",   "CAP = new BigDecimal(\"500.00\")"},
        {"LoanRegistry.java", "public void record(Loan loan)"},
        {"Member.java",       "activeLoans.size() >= 5"},
        {"Loan.java",         "DEFAULT_TERM_DAYS = 21"},
        {"Catalog.java",      "public List<Book> searchByTitle(String needle)"},
        {"Book.java",         "public boolean equals(Object o)"},
        {"ReportService.java", "public Map<String, Integer> loansByGenre()"},
        null,
    };

    private static final int TOP_K = 8;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "sample-repo");

        // -----------------------------------------------------------
        // 1. Pipeline construction.
        // -----------------------------------------------------------
        Embedder    emb = new MiniLmEmbedder();    // was: new StubEmbedder()
        LlmClient   llm = new OllamaLlm();         // was: new StubLlmClient()
        RepoIndexer idx = new RepoIndexer(emb);

        // -----------------------------------------------------------
        // 2. Index the corpus; report count + wall-clock time.
        // -----------------------------------------------------------
        Instant t0 = Instant.now();
        idx.indexDirectory(dir);
        Duration elapsed = Duration.between(t0, Instant.now());
        System.out.printf("Indexed %d chunk(s) from %s in %d ms%n%n",
                idx.size(), dir, elapsed.toMillis());

        // -----------------------------------------------------------
        // 3. Ask each question.  Print retriever hits + LLM answer.
        //    (retrieve() and answer() both re-rank; the redundancy is
        //    deliberate so the trace shows what the retriever picked.)
        // -----------------------------------------------------------
        RagAssistant ra = new RagAssistant(
                idx, emb, llm, Path.of("prompts/rag.txt"));
        int scored = 0, hitCount = 0;
        for (int qi = 0; qi < QUESTIONS.length; qi++) {
            String q = QUESTIONS[qi];
            System.out.println("=== Q" + (qi + 1) + ": " + q);
            List<Chunk> hits = ra.retrieve(q, TOP_K);
            for (Chunk c : hits) {
                System.out.println("  hit: " + c.source()
                        + ":" + c.startLine() + "-" + c.endLine());
            }
            String[] exp = EXPECTED[qi];
            if (exp != null) {
                boolean ok = isHit(hits, exp);
                scored++;
                if (ok) hitCount++;
                System.out.println("  retrieval: " + (ok ? "HIT" : "MISS")
                        + " (expected " + exp[0] + " :: " + exp[1] + ")");
            } else {
                System.out.println("  retrieval: (not in corpus; not scored)");
            }
            Instant a0 = Instant.now();
            String answer = ra.answer(q, TOP_K);
            System.out.println("--- A: " + answer.strip());
            System.out.printf("  (answered in %.1f s)%n%n",
                    Duration.between(a0, Instant.now()).toMillis() / 1000.0);
        }
        System.out.printf("Retrieval hit-rate @%d: %d/%d = %.2f%n",
                TOP_K, hitCount, scored, scored == 0 ? 0.0 : (double) hitCount / scored);
    }

    static boolean isHit(List<Chunk> hits, String[] exp) {
        for (Chunk c : hits) {
            if (!c.source().endsWith(exp[0])) continue;
            for (int m = 1; m < exp.length; m++) {
                if (c.content().contains(exp[m])) return true;
            }
        }
        return false;
    }
}
