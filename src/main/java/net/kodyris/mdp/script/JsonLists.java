package net.kodyris.mdp.script;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backs "<value> exists in <file>.json".
 *
 * A list file is JSON, either
 *   - an array of values  ["uuid-1", "uuid-2", 7, true]   -> membership of any string/number/boolean
 *   - an object           {"uuid-1": {...}, "uuid-2": 1}  -> membership of the keys
 * Paths are relative to the ModDataPacks folder and cannot leave it.
 * Files are re-read automatically when they change on disk, so no /mdp reload is needed after editing one.
 */
public final class JsonLists {
    public static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final Pattern UUID = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private record Cached(long mtime, long size, Set<String> exact, Set<String> uuidsLower) {}

    private final Path root;
    private final Map<String, Cached> cache = new HashMap<>();

    public JsonLists(Path root) { this.root = root.toAbsolutePath().normalize(); }

    /** Static check used when a script is loaded. Returns an error message, or null if the path is acceptable. */
    public static String checkPath(String rel) {
        if (rel == null || rel.isBlank()) return "empty list file name";
        if (rel.length() > 200) return "list file name is too long";
        if (rel.contains("\\") || rel.contains(":") || rel.startsWith("/"))
            return "list file '" + rel + "' must be a relative path under ModDataPacks (use '/')";
        for (String seg : rel.split("/", -1)) {
            if (seg.isEmpty()) return "list file '" + rel + "' has an empty path segment";
            if (seg.equals("..") || seg.equals(".")) return "list file '" + rel + "' cannot use '.' or '..'";
        }
        if (!rel.endsWith(".json")) return "list file '" + rel + "' must end in .json";
        return null;
    }

    public boolean contains(String rel, String value) throws ListException {
        String bad = checkPath(rel);
        if (bad != null) throw new ListException(bad);
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root)) throw new ListException("list file '" + rel + "' is outside ModDataPacks");

        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(p, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            cache.remove(rel);
            throw new ListException("list file '" + rel + "' not found in ModDataPacks");
        } catch (IOException e) {
            throw new ListException("could not read list file '" + rel + "': " + e.getMessage());
        }
        if (!attrs.isRegularFile()) throw new ListException("list file '" + rel + "' is not a file");

        // A symlink must not lead out of ModDataPacks either.
        try {
            Path real = p.toRealPath();
            if (!real.startsWith(root.toRealPath())) throw new ListException("list file '" + rel + "' is outside ModDataPacks");
        } catch (IOException e) {
            throw new ListException("could not read list file '" + rel + "': " + e.getMessage());
        }

        long mtime = attrs.lastModifiedTime().toMillis();
        long size = attrs.size();
        Cached c = cache.get(rel);
        if (c == null || c.mtime() != mtime || c.size() != size) {
            if (size > MAX_BYTES) throw new ListException("list file '" + rel + "' is larger than " + (MAX_BYTES >> 20) + " MB");
            c = load(rel, p, mtime, size);
            cache.put(rel, c);
        }
        if (c.exact().contains(value)) return true;
        return UUID.matcher(value).matches() && c.uuidsLower().contains(value.toLowerCase(Locale.ROOT));
    }

    private static Cached load(String rel, Path p, long mtime, long size) throws ListException {
        String text;
        try {
            text = Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ListException("could not read list file '" + rel + "': " + e.getMessage());
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);

        Object root;
        try {
            root = new MiniJson(text).parseRoot();
        } catch (ListException e) {
            throw new ListException("list file '" + rel + "' is not valid JSON: " + e.getMessage());
        }

        Set<String> exact = new HashSet<>();
        if (root instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) exact.add(s);
                else if (o instanceof MiniJson.Num n) exact.add(n.raw());
                else if (o instanceof Boolean b) exact.add(b.toString());
                // nested arrays/objects and null are not list values
            }
        } else if (root instanceof Map<?, ?> map) {
            for (Object k : map.keySet()) exact.add((String) k);
        } else {
            throw new ListException("list file '" + rel + "' must be a JSON array or object");
        }

        Set<String> lower = new HashSet<>();
        for (String s : exact) if (UUID.matcher(s).matches()) lower.add(s.toLowerCase(Locale.ROOT));
        return new Cached(mtime, size, exact, lower);
    }

    // ------------------------------------------------------------------ minimal JSON parser

    static final class MiniJson {
        record Num(String raw) {}

        private static final Pattern NUM = Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
        private static final int MAX_DEPTH = 64;

        private final String s;
        private int i = 0;
        private int depth = 0;

        MiniJson(String s) { this.s = s; }

        Object parseRoot() throws ListException {
            ws();
            Object v = value();
            ws();
            if (i != s.length()) throw err("unexpected data after the value");
            return v;
        }

        private ListException err(String m) {
            int line = 1, col = 1;
            for (int k = 0; k < Math.min(i, s.length()); k++) {
                if (s.charAt(k) == '\n') { line++; col = 1; } else col++;
            }
            return new ListException(m + " (line " + line + ", column " + col + ")");
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() throws ListException {
            if (i >= s.length()) throw err("unexpected end of file");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (c == '-' || (c >= '0' && c <= '9')) {
                Matcher m = NUM.matcher(s);
                m.region(i, s.length());
                if (!m.lookingAt()) throw err("bad number");
                i = m.end();
                return new Num(m.group());
            }
            throw err("unexpected character '" + c + "'");
        }

        private Map<String, Object> object() throws ListException {
            if (++depth > MAX_DEPTH) throw err("nesting is too deep");
            i++;                                   // {
            Map<String, Object> out = new LinkedHashMap<>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; depth--; return out; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw err("expected a string key");
                String key = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':' after key");
                i++;
                ws();
                out.put(key, value());
                ws();
                if (i >= s.length()) throw err("unexpected end of file in object");
                char c = s.charAt(i++);
                if (c == '}') break;
                if (c != ',') { i--; throw err("expected ',' or '}'"); }
            }
            depth--;
            return out;
        }

        private List<Object> array() throws ListException {
            if (++depth > MAX_DEPTH) throw err("nesting is too deep");
            i++;                                   // [
            List<Object> out = new ArrayList<>();
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; depth--; return out; }
            while (true) {
                ws();
                out.add(value());
                ws();
                if (i >= s.length()) throw err("unexpected end of file in array");
                char c = s.charAt(i++);
                if (c == ']') break;
                if (c != ',') { i--; throw err("expected ',' or ']'"); }
            }
            depth--;
            return out;
        }

        private String string() throws ListException {
            i++;                                   // opening quote
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw err("bad \\u escape");
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw err("bad \\u escape");
                            }
                            i += 4;
                        }
                        default -> { i--; throw err("unknown escape '\\" + e + "'"); }
                    }
                } else {
                    sb.append(c);
                }
            }
            throw err("unterminated string");
        }
    }
}
