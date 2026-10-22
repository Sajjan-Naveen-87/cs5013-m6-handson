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
