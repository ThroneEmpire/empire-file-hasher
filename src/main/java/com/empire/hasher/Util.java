package com.empire.hasher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.MalformedInputException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Stateless helper functions used by {@link Main}. Everything here is a pure
 * function of its arguments (no shared mutable state), which keeps it easy to
 * unit-test in isolation.
 */
final class Util {

    static final int BUFFER_SIZE = 1 << 16; // 64 KiB

    private Util() {}

    // --- hashing -----------------------------------------------------------

    static String sha256(Path p) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(p)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * Root-relative path with forward slashes. Backslashes are only converted
     * where they are the separator (Windows); on Unix a backslash is a legal
     * filename character and must be kept as-is.
     */
    static String toRelative(Path root, Path p) {
        String rel = root.relativize(p).toString();
        return "\\".equals(p.getFileSystem().getSeparator()) ? rel.replace('\\', '/') : rel;
    }

    /** Short human-readable reason for an I/O failure on a single path. */
    static String describe(Exception e) {
        if (e instanceof InvalidPathException) return "file name can't be represented in the current encoding";
        if (e instanceof AccessDeniedException) return "permission denied";
        if (e instanceof NoSuchFileException) return "no longer exists";
        if (e instanceof FileSystemException fse && fse.getReason() != null) return fse.getReason();
        return e.toString();
    }

    // --- manifest I/O ------------------------------------------------------

    /**
     * One manifest line in {@code sha256sum} format. Like GNU sha256sum, a
     * name containing a backslash or line break is escaped and the line is
     * prefixed with a backslash, so every entry stays on a single line.
     */
    static String manifestLine(String hash, String rel) {
        if (rel.indexOf('\\') < 0 && rel.indexOf('\n') < 0 && rel.indexOf('\r') < 0) {
            return hash + "  " + rel;
        }
        String escaped = rel.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
        return "\\" + hash + "  " + escaped;
    }

    static String unescapeName(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(c).append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** A parsed manifest: its entries plus any lines that couldn't be parsed ("line N: text"). */
    record Manifest(Map<String, String> entries, List<String> malformed) {}

    static Map<String, String> loadManifest(Path p) throws IOException {
        return readManifest(p).entries();
    }

    private static final Pattern BSD_LINE = Pattern.compile("SHA256 \\((.*)\\) = ([0-9a-fA-F]+)");

    /**
     * Parse every line format {@code sha256sum -c} accepts: "hash  name"
     * (text mode), "hash *name" (binary mode, e.g. {@code sha256sum -b}),
     * either with a leading backslash for escaped names, and the BSD-style
     * "SHA256 (name) = hash". Anything else is reported in
     * {@link Manifest#malformed} rather than silently skipped.
     */
    static Manifest readManifest(Path p) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> malformed = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new IOException(p + " is not valid UTF-8 text — is it really a manifest?", e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            boolean escaped = line.startsWith("\\");
            String body = escaped ? line.substring(1) : line;
            String hash = null, name = null;
            Matcher bsd = BSD_LINE.matcher(body);
            if (bsd.matches()) {
                name = bsd.group(1);
                hash = bsd.group(2);
            } else {
                int sep = body.indexOf(' ');
                if (sep > 0 && sep + 2 < body.length()) {
                    char mode = body.charAt(sep + 1);
                    if (mode == ' ' || mode == '*') {
                        hash = body.substring(0, sep);
                        name = body.substring(sep + 2);
                    }
                }
            }
            if (hash == null) {
                String shown = line.length() > 80 ? line.substring(0, 80) + "…" : line;
                malformed.add("line " + (i + 1) + ": " + shown);
                continue;
            }
            out.put(escaped ? unescapeName(name) : name, hash);
        }
        return new Manifest(out, malformed);
    }

    /**
     * Write the manifest via a temp file + rename. The temp file is fsynced
     * before the rename (and the directory after), so a power loss can't
     * leave a renamed-but-empty manifest behind.
     */
    static void writeManifestAtomic(Path p, Map<String, String> entries) throws IOException {
        List<String> keys = new ArrayList<>(entries.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) sb.append(manifestLine(entries.get(k), k)).append('\n');
        ByteBuffer buf = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));

        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true);
        }
        try {
            Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
        fsyncDirectory(p.toAbsolutePath().getParent());
    }

    private static void fsyncDirectory(Path dir) {
        if (dir == null) return;
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException ignored) {
            // Not supported on every platform (e.g. Windows); best effort.
        }
    }

    // --- ignore patterns ---------------------------------------------------

    /**
     * Clean up raw ignore entries. A leading "!" (negation) is kept in front
     * of the result; a leading "\!" means a literal "!" and is kept as the
     * glob escape "\!" so it isn't mistaken for a negation.
     */
    static List<String> normalizeIgnores(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String entry : raw) {
            String e = entry.trim();
            String prefix = "";
            if (e.startsWith("\\!")) {
                prefix = "\\!";
                e = e.substring(2);
            } else if (e.startsWith("!")) {
                prefix = "!";
                e = e.substring(1).trim();
            }
            if (e.isEmpty()) continue;
            e = e.replace('\\', '/');
            boolean dir = e.endsWith("/");
            if (prefix.isEmpty()) {
                while (e.startsWith("./")) e = e.substring(2);
                while (e.startsWith("/")) e = e.substring(1);
            }
            while (e.endsWith("/")) e = e.substring(0, e.length() - 1);
            if (e.isEmpty()) continue;
            out.add(prefix + (dir ? e + "/" : e));
        }
        return out;
    }

    /** One compiled ignore pattern. {@code negate} rules ("!pattern") un-ignore. */
    record IgnoreRule(PathMatcher matcher, boolean negate) {}

    /**
     * Every pattern matches the path itself and, when that path is a
     * directory, everything beneath it — so "build" and "build/" both skip
     * the whole build directory. Rules apply in order and the last match
     * wins, so "!pattern" re-includes paths an earlier rule ignored. Throws
     * {@link IllegalArgumentException} for a malformed glob.
     */
    static List<IgnoreRule> compileIgnoreMatchers(List<String> normalized) {
        List<IgnoreRule> out = new ArrayList<>();
        FileSystem fs = FileSystems.getDefault();
        for (String ig : normalized) {
            boolean negate = ig.startsWith("!");
            String base = negate ? ig.substring(1) : ig;
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            try {
                addMatchers(out, fs, base, negate);
                // Java's glob treats a leading "**/" as requiring at least one
                // directory level, so it would not match the pattern at the root.
                // Add a root-anchored variant so "**/x" behaves gitignore-style:
                // matching at any depth, including zero.
                if (base.startsWith("**/")) {
                    addMatchers(out, fs, base.substring(3), negate);
                }
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                        "Invalid ignore pattern '" + ig + "': " + e.getDescription(), e);
            }
        }
        return out;
    }

    private static void addMatchers(List<IgnoreRule> out, FileSystem fs, String base, boolean negate) {
        if (base.isEmpty()) return;
        out.add(new IgnoreRule(fs.getPathMatcher("glob:" + base), negate));
        out.add(new IgnoreRule(fs.getPathMatcher("glob:" + base + "/**"), negate));
    }

    static boolean isIgnored(String rel, List<IgnoreRule> rules) {
        if (rules == null || rules.isEmpty()) return false;
        Path p = Paths.get(rel);
        boolean ignored = false;
        for (IgnoreRule r : rules) {
            if (r.matcher().matches(p)) ignored = !r.negate();
        }
        return ignored;
    }

    static boolean hasNegation(List<IgnoreRule> rules) {
        for (IgnoreRule r : rules) if (r.negate()) return true;
        return false;
    }

    /**
     * True if {@code p} is part of the manifest family (the db itself, its
     * .partial, .bak* backups, or .tmp staging files) living in {@code dbDir}.
     */
    static boolean isManifestFile(Path dbDir, String dbFilename, String partialSuffix, Path p) {
        Path parent = p.getParent();
        if (parent == null || dbDir == null || !parent.equals(dbDir)) return false;
        String name = p.getFileName().toString();
        return name.equals(dbFilename)
                || name.startsWith(dbFilename + ".bak")
                || name.equals(dbFilename + partialSuffix)
                || name.equals(dbFilename + partialSuffix + ".tmp")
                || name.equals(dbFilename + ".tmp");
    }

    // --- db path resolution ------------------------------------------------

    /**
     * Resolve the manifest location. A bare name (no separators) lives in
     * {@code root}; a path is taken relative to the current working directory.
     * Pure: performs no filesystem access.
     */
    static Path resolveDbPath(Path root, String dbArg, String defaultName) {
        if (dbArg == null) {
            return root.resolve(defaultName);
        } else if (dbArg.contains("/") || dbArg.contains("\\")) {
            return Paths.get(dbArg).toAbsolutePath().normalize();
        } else {
            return root.resolve(dbArg).normalize();
        }
    }

    // --- manifest diff -----------------------------------------------------

    static final class DiffResult {
        final List<String> added = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        final List<String> changed = new ArrayList<>();
        boolean isEmpty() { return added.isEmpty() && removed.isEmpty() && changed.isEmpty(); }
    }

    /** Pure comparison of two manifests (old -> new), with sorted results. */
    static DiffResult computeDiff(Map<String, String> ma, Map<String, String> mb) {
        DiffResult r = new DiffResult();
        for (Map.Entry<String, String> e : mb.entrySet()) {
            String prev = ma.get(e.getKey());
            if (prev == null) r.added.add(e.getKey());
            else if (!prev.equalsIgnoreCase(e.getValue())) r.changed.add(e.getKey());
        }
        for (String k : ma.keySet()) if (!mb.containsKey(k)) r.removed.add(k);
        Collections.sort(r.added);
        Collections.sort(r.removed);
        Collections.sort(r.changed);
        return r;
    }
}
