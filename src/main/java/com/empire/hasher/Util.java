package com.empire.hasher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

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
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String toRelative(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    // --- manifest I/O ------------------------------------------------------

    static Map<String, String> loadManifest(Path p) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            int sep = line.indexOf("  ");
            if (sep < 0) continue;
            out.put(line.substring(sep + 2), line.substring(0, sep));
        }
        return out;
    }

    static void writeManifestAtomic(Path p, Map<String, String> entries) throws IOException {
        List<String> keys = new ArrayList<>(entries.keySet());
        Collections.sort(keys);
        List<String> lines = new ArrayList<>(keys.size());
        for (String k : keys) lines.add(entries.get(k) + "  " + k);
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // --- ignore patterns ---------------------------------------------------

    static List<String> normalizeIgnores(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String entry : raw) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            e = e.replace('\\', '/');
            boolean dir = e.endsWith("/");
            while (e.startsWith("./")) e = e.substring(2);
            while (e.startsWith("/")) e = e.substring(1);
            while (e.endsWith("/")) e = e.substring(0, e.length() - 1);
            if (e.isEmpty()) continue;
            out.add(dir ? e + "/" : e);
        }
        return out;
    }

    static List<PathMatcher> compileIgnoreMatchers(List<String> normalized) {
        List<PathMatcher> out = new ArrayList<>();
        FileSystem fs = FileSystems.getDefault();
        for (String ig : normalized) {
            boolean dir = ig.endsWith("/");
            String base = dir ? ig.substring(0, ig.length() - 1) : ig;
            addMatchers(out, fs, base, dir);
            // Java's glob treats a leading "**/" as requiring at least one
            // directory level, so it would not match the pattern at the root.
            // Add a root-anchored variant so "**/x" behaves gitignore-style:
            // matching at any depth, including zero.
            if (base.startsWith("**/")) {
                addMatchers(out, fs, base.substring(3), dir);
            }
        }
        return out;
    }

    private static void addMatchers(List<PathMatcher> out, FileSystem fs, String base, boolean dir) {
        if (base.isEmpty()) return;
        out.add(fs.getPathMatcher("glob:" + base));
        if (dir) out.add(fs.getPathMatcher("glob:" + base + "/**"));
    }

    static boolean isIgnored(String rel, List<PathMatcher> matchers) {
        if (matchers == null || matchers.isEmpty()) return false;
        Path p = Paths.get(rel);
        for (PathMatcher m : matchers) {
            if (m.matches(p)) return true;
        }
        return false;
    }

    /**
     * True if {@code p} is part of the manifest family (the db itself, its
     * .partial, .bak* backups, or .tmp staging file) living in {@code dbDir}.
     */
    static boolean isManifestFile(Path dbDir, String dbFilename, String partialSuffix, Path p) {
        Path parent = p.getParent();
        if (parent == null || dbDir == null || !parent.equals(dbDir)) return false;
        String name = p.getFileName().toString();
        return name.equals(dbFilename)
                || name.startsWith(dbFilename + ".bak")
                || name.equals(dbFilename + partialSuffix)
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
            return root.resolve(dbArg);
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
