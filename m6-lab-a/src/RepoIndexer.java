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
