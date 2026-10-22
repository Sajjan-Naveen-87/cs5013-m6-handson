import java.nio.file.Path;
import java.util.List;

/**
 * Retrieval-only evaluation (no LLM): hit@1/3/8 and MRR for the
 * method-level chunker vs the 50-line chunker, using Main.QUESTIONS and
 * Main.EXPECTED. Run after `make build`:
 *   javac -d build -cp "build:libs/langchain4j/*" runs/RetrievalEval.java
 *   java -cp "build:libs/langchain4j/*" RetrievalEval
 */
public class RetrievalEval {
    public static void main(String[] args) throws Exception {
        Embedder emb = new MiniLmEmbedder();
        for (boolean fixed : new boolean[]{false, true}) {
            RepoIndexer idx = new RepoIndexer(emb, fixed);
            idx.indexDirectory(Path.of("sample-repo"));
            RagAssistant ra = new RagAssistant(idx, emb, new StubLlmClient(), Path.of("prompts/rag.txt"));
            int n = 0, h1 = 0, h3 = 0, h8 = 0;
            long ctxChars = 0;
            double mrr = 0;
            StringBuilder ranks = new StringBuilder();
            for (int q = 0; q < Main.QUESTIONS.length; q++) {
                if (Main.EXPECTED[q] == null) continue;
                List<Chunk> all = ra.retrieve(Main.QUESTIONS[q], idx.size());
                int rank = 0;
                for (int r = 0; r < all.size(); r++) {
                    if (Main.isHit(List.of(all.get(r)), Main.EXPECTED[q])) { rank = r + 1; break; }
                }
                n++;
                ctxChars += ra.buildPrompt(Main.QUESTIONS[q], all.subList(0, Math.min(8, all.size()))).length();
                if (rank == 1) h1++;
                if (rank >= 1 && rank <= 3) h3++;
                if (rank >= 1 && rank <= 8) h8++;
                if (rank > 0) mrr += 1.0 / rank;
                ranks.append(rank).append(' ');
            }
            System.out.printf("%-16s chunks=%-3d hit@1=%d/%d hit@3=%d/%d hit@8=%d/%d MRR=%.2f  ranks=[%s]  top-8 covers %.0f%% of index, avg top-8 prompt=%d chars%n",
                    fixed ? "fixed-50-lines" : "method-level", idx.size(), h1, n, h3, n, h8, n, mrr / n,
                    ranks.toString().trim(), 100.0 * Math.min(8, idx.size()) / idx.size(), ctxChars / n);
        }
    }
}
