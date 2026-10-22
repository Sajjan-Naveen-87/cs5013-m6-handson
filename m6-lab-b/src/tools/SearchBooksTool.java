import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * search_books: targeted lookup by author and year range.
 *
 * Expected argument format: {@code author|year_from|year_to}
 * where empty parts mean "no constraint".  Example:
 * <pre>
 *   search_books("Ursula K. Le Guin|1970|1979")
 * </pre>
 * Any part may be left empty, e.g. {@code "|1990|2000"} for a
 * year-range query across all authors.
 *
 * Author matching is token-based and case-insensitive: every word of
 * the query (ignoring initials and punctuation) must appear in the
 * author's name, so "Le Guin", "ursula le guin" and "Ursula K. Le Guin"
 * all match. Years are inclusive. Returns a JSON array (possibly "[]").
 */
public class SearchBooksTool implements Tool {

    /** One flat book object per line of books.json. */
    private static final Pattern BOOK = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern FIELD_STR = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern FIELD_YEAR = Pattern.compile("\"year\"\\s*:\\s*(-?\\d+)");

    private final Path catalogue;

    public SearchBooksTool() {
        this(Path.of("books.json"));
    }

    public SearchBooksTool(Path catalogue) {
        this.catalogue = catalogue;
    }

    @Override
    public String name() {
        return "search_books";
    }

    @Override
    public String call(String arg) {
        String[] parts = (arg == null ? "" : arg.strip()).split("\\|", -1);
        String author = parts.length > 0 ? clean(parts[0]) : "";
        Integer from, to;
        try {
            from = parts.length > 1 ? parseYear(parts[1]) : null;
            to = parts.length > 2 ? parseYear(parts[2]) : null;
        } catch (NumberFormatException e) {
            return "ERROR: bad argument '" + arg + "'; expected author|year_from|year_to with numeric years";
        }
        List<String> authorTokens = tokens(author);

        String json;
        try {
            json = Files.readString(catalogue);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + catalogue, e);
        }

        List<String> out = new ArrayList<>();
        Matcher m = BOOK.matcher(json);
        while (m.find()) {
            String obj = m.group();
            String bookAuthor = stringField(obj, "author");
            Matcher ym = FIELD_YEAR.matcher(obj);
            if (bookAuthor == null || !ym.find()) continue;
            int year = Integer.parseInt(ym.group(1));
            if (from != null && year < from) continue;
            if (to != null && year > to) continue;
            if (!tokens(bookAuthor).containsAll(authorTokens)) continue;
            out.add("  " + obj.strip());
        }
        return out.isEmpty() ? "[]" : "[\n" + String.join(",\n", out) + "\n]";
    }

    /** Strip quotes / "author=" style noise the model sometimes adds. */
    private static String clean(String s) {
        s = s.strip();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
        return s.replaceFirst("(?i)^author\\s*[:=]\\s*", "").strip();
    }

    private static Integer parseYear(String s) {
        s = clean(s).replaceFirst("(?i)^year_(from|to)\\s*[:=]\\s*", "");
        return s.isEmpty() ? null : Integer.valueOf(s.strip());
    }

    /** Lower-cased words of length >= 2 (drops initials like "K." and punctuation). */
    static List<String> tokens(String s) {
        return Arrays.stream(s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> t.length() >= 2)
                .toList();
    }

    private static String stringField(String obj, String name) {
        Matcher f = FIELD_STR.matcher(obj);
        while (f.find()) {
            if (f.group(1).equals(name)) return f.group(2);
        }
        return null;
    }
}
