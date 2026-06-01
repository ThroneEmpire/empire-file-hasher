package com.empire.hasher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Stream;

import com.empire.hasher.Util.DiffResult;
import static com.empire.hasher.Util.*;

public class Main {

    // Set once during argument parsing, then read-only for the rest of the run.
    // Package-private so tests in the same package can configure them.
    static String DB_FILENAME = "hashes.db";
    static Path DB_DIR; // directory holding the manifest family
    static final String IGNORE_FILENAME = ".hashignore";
    static final String PARTIAL_SUFFIX = ".partial";

    enum Verbosity { QUIET, NORMAL, VERBOSE }

    public static void main(String[] args) throws Exception {
        String dirArg = null;
        int threads = 1;
        List<String> ignoreArgs = new ArrayList<>();
        Verbosity verbosity = Verbosity.NORMAL;
        String diffA = null, diffB = null;
        String dbArg = null;
        String ignoreFileArg = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--diff")) {
                if (i + 2 >= args.length) {
                    System.err.println("--diff requires two manifest paths: --diff OLD.db NEW.db");
                    System.exit(2);
                }
                diffA = args[++i];
                diffB = args[++i];
            } else if (a.equals("-q") || a.equals("--quiet")) {
                verbosity = Verbosity.QUIET;
            } else if (a.equals("-v") || a.equals("--verbose")) {
                verbosity = Verbosity.VERBOSE;
            } else if (a.equals("-t") || a.equals("--threads")) {
                if (i + 1 >= args.length) {
                    System.err.println("Missing value for " + a);
                    System.exit(2);
                }
                try {
                    threads = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid thread count: " + args[i]);
                    System.exit(2);
                }
                if (threads < 1) {
                    System.err.println("Thread count must be >= 1");
                    System.exit(2);
                }
            } else if (a.equals("-i") || a.equals("--ignore")) {
                if (i + 1 >= args.length) {
                    System.err.println("Missing value for " + a);
                    System.exit(2);
                }
                ignoreArgs.add(args[++i]);
            } else if (a.equals("-d") || a.equals("--db")) {
                if (i + 1 >= args.length) {
                    System.err.println("Missing value for " + a);
                    System.exit(2);
                }
                dbArg = args[++i].trim();
                if (dbArg.isEmpty()) {
                    System.err.println("Database name must not be empty.");
                    System.exit(2);
                }
            } else if (a.equals("--ignore-file")) {
                if (i + 1 >= args.length) {
                    System.err.println("Missing value for " + a);
                    System.exit(2);
                }
                ignoreFileArg = args[++i].trim();
                if (ignoreFileArg.isEmpty()) {
                    System.err.println("Ignore-file path must not be empty.");
                    System.exit(2);
                }
            } else if (a.equals("-h") || a.equals("--help")) {
                System.out.println("Usage: empire-file-hasher [DIR] [options]");
                System.out.println("       empire-file-hasher --diff OLD.db NEW.db");
                System.out.println();
                System.out.println("  DIR              directory to hash (default: current directory)");
                System.out.println("  -d, --db NAME    manifest location (default: hashes.db in DIR).");
                System.out.println("                   A bare name lives in DIR; a path (e.g. ./config/my.db)");
                System.out.println("                   is resolved relative to the current directory.");
                System.out.println("  -t, --threads    number of hashing threads (default: 1)");
                System.out.println("  -i, --ignore     path to ignore (repeatable). Trailing / matches a directory subtree.");
                System.out.println("  --ignore-file F  read ignore patterns from F (default: .hashignore in DIR).");
                System.out.println("  -q, --quiet      suppress per-file output and progress");
                System.out.println("  -v, --verbose    print every file as it is processed (default: progress line only)");
                System.out.println("  --diff           compare two manifest files and print differences");
                return;
            } else if (dirArg == null) {
                dirArg = a;
            } else {
                System.err.println("Unexpected argument: " + a);
                System.exit(2);
            }
        }

        if (diffA != null) {
            diffManifests(Paths.get(diffA), Paths.get(diffB));
            return;
        }

        Path root = (dirArg != null ? Paths.get(dirArg) : Paths.get("")).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("Not a directory: " + root);
            System.exit(2);
        }

        // Resolve the manifest location. A bare name lives in DIR (the common
        // case); a path is resolved relative to the current working directory.
        Path db = resolveDbPath(root, dbArg, DB_FILENAME);
        boolean dbIsPath = dbArg != null && (dbArg.contains("/") || dbArg.contains("\\"));
        if (dbIsPath) {
            if (Files.isDirectory(db)) {
                System.err.println("--db points to a directory, not a file: " + db);
                System.exit(2);
            }
            Path parent = db.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                System.err.println("Directory for --db does not exist: " + parent);
                System.exit(2);
            }
        }
        // DB_FILENAME / DB_DIR drive the derived family (.partial/.bak/.tmp)
        // and manifest exclusion, wherever the db ends up living.
        DB_FILENAME = db.getFileName().toString();
        DB_DIR = db.getParent();

        List<String> ignores = new ArrayList<>();
        Path ignoreFile;
        boolean ignoreFileExplicit = ignoreFileArg != null;
        if (ignoreFileExplicit) {
            ignoreFile = Paths.get(ignoreFileArg).toAbsolutePath().normalize();
            if (!Files.exists(ignoreFile)) {
                System.err.println("--ignore-file not found: " + ignoreFile);
                System.exit(2);
            }
        } else {
            ignoreFile = root.resolve(IGNORE_FILENAME);
        }
        if (Files.exists(ignoreFile)) {
            for (String line : Files.readAllLines(ignoreFile, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                ignores.add(t);
            }
        }
        ignores.addAll(ignoreArgs);
        List<String> normalizedIgnores = normalizeIgnores(ignores);

        System.out.println("File Hasher");
        System.out.println("Working directory: " + root);
        System.out.println("Hash database:     " + db);
        System.out.println("Threads:           " + threads);
        if (!normalizedIgnores.isEmpty()) {
            System.out.println("Ignoring:");
            for (String ig : normalizedIgnores) System.out.println("  " + ig);
        }
        System.out.println();

        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            Path partial = db.resolveSibling(DB_FILENAME + PARTIAL_SUFFIX);
            if (Files.exists(partial)) {
                Map<String, String> prior = loadManifest(partial);
                System.out.println("Found a partial hash from a previous interrupted run: "
                        + partial.getFileName() + " (" + prior.size() + " entries)");
                System.out.println("  [r] resume — continue hashing the remaining files");
                System.out.println("  [d] discard the partial and start over");
                System.out.println("  [q] quit");
                System.out.print("Choice: ");
                String choice = in.readLine();
                if (choice == null) return;
                switch (choice.trim().toLowerCase(Locale.ROOT)) {
                    case "r" -> { hashAll(root, db, threads, prior, normalizedIgnores, verbosity); return; }
                    case "d" -> {
                        Files.delete(partial);
                        System.out.println("Partial discarded.");
                    }
                    default -> { System.out.println("Exiting."); return; }
                }
            }

            if (!Files.exists(db)) {
                System.out.println("No " + DB_FILENAME + " found in this directory.");
                if (confirm(in, "Do you want to hash every file here (recursively) and create one? [y/N]: ")) {
                    hashAll(root, db, threads, null, normalizedIgnores, verbosity);
                } else {
                    System.out.println("Nothing to do. Exiting.");
                }
            } else {
                System.out.println(DB_FILENAME + " exists.");
                System.out.println("  [v] verify existing files against " + DB_FILENAME);
                System.out.println("  [u] update — verify, then add new files and drop missing entries");
                System.out.println("  [r] rehash everything and overwrite " + DB_FILENAME);
                System.out.println("  [q] quit");
                System.out.print("Choice: ");
                String choice = in.readLine();
                if (choice == null) return;
                switch (choice.trim().toLowerCase(Locale.ROOT)) {
                    case "v" -> verify(root, db, threads, normalizedIgnores, verbosity, in);
                    case "u" -> update(root, db, threads, normalizedIgnores, verbosity, in);
                    case "r" -> {
                        System.out.println();
                        System.out.println("WARNING: rehashing will overwrite the existing " + DB_FILENAME + ".");
                        System.out.println("The old manifest will be backed up before the new one is written.");
                        if (confirm(in, "Are you sure you want to rehash everything? [y/N]: ")) {
                            Path backup = backupDb(db);
                            System.out.println("Backed up existing manifest to " + backup.getFileName());
                            hashAll(root, db, threads, null, normalizedIgnores, verbosity);
                        } else {
                            System.out.println("Rehash cancelled.");
                        }
                    }
                    default -> System.out.println("Exiting.");
                }
            }
        }
    }

    static Path backupDb(Path db) throws IOException {
        Path parent = db.getParent();
        Path primary = parent.resolve(DB_FILENAME + ".bak");
        if (!Files.exists(primary)) {
            Files.copy(db, primary, StandardCopyOption.REPLACE_EXISTING);
            return primary;
        }
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path stamped = parent.resolve(DB_FILENAME + ".bak." + stamp);
        Files.copy(db, stamped, StandardCopyOption.REPLACE_EXISTING);
        return stamped;
    }

    private static boolean confirm(BufferedReader in, String prompt) throws IOException {
        System.out.print(prompt);
        String line = in.readLine();
        return line != null && line.trim().equalsIgnoreCase("y");
    }

    static void hashAll(Path root, Path db, int threads, Map<String, String> prior, List<String> ignores, Verbosity verbosity) throws Exception {
        Path partial = db.resolveSibling(DB_FILENAME + PARTIAL_SUFFIX);
        List<Path> allFiles = collectFiles(root, ignores);

        // If resuming, drop any prior entries whose files no longer exist on disk.
        if (prior != null) {
            int before = prior.size();
            prior.keySet().removeIf(rel -> !Files.exists(root.resolve(rel)));
            int dropped = before - prior.size();
            if (dropped > 0) System.out.println("Dropped " + dropped + " partial entries whose files no longer exist.");
        }

        List<Path> todo = new ArrayList<>();
        for (Path p : allFiles) {
            String rel = toRelative(root, p);
            if (prior == null || !prior.containsKey(rel)) todo.add(p);
        }

        int total = todo.size();
        int already = prior == null ? 0 : prior.size();
        if (prior != null) {
            System.out.println("Resuming: " + already + " already hashed, " + total + " remaining.");
        }
        System.out.println("Hashing " + total + " files with " + threads + (threads == 1 ? " thread..." : " threads..."));

        AtomicReferenceArray<String> newLines = new AtomicReferenceArray<>(total);
        AtomicBoolean completed = new AtomicBoolean(false);
        Object finalizeLock = new Object();

        Thread hook = new Thread(() -> {
            synchronized (finalizeLock) {
                if (completed.get()) return;
                try {
                    Map<String, String> snapshot = new LinkedHashMap<>();
                    if (prior != null) snapshot.putAll(prior);
                    for (int i = 0; i < newLines.length(); i++) {
                        String s = newLines.get(i);
                        if (s == null) continue;
                        int sep = s.indexOf("  ");
                        if (sep > 0) snapshot.put(s.substring(sep + 2), s.substring(0, sep));
                    }
                    writeManifestAtomic(partial, snapshot);
                    System.err.println();
                    System.err.println("Interrupted. Saved " + snapshot.size()
                            + " entries to " + partial.getFileName() + ". Run again to resume.");
                } catch (IOException e) {
                    System.err.println("Failed to save partial: " + e);
                }
            }
        }, "file-hasher-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        final Progress prog = new Progress(allFiles.size(), verbosity, already);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>(total);
            for (int idx = 0; idx < total; idx++) {
                final int slot = idx;
                final Path p = todo.get(idx);
                futures.add(pool.submit(() -> {
                    String rel = toRelative(root, p);
                    String digest = sha256(p);
                    newLines.set(slot, digest + "  " + rel);
                    prog.tick(rel);
                    return null;
                }));
            }
            awaitAll(futures);
        } finally {
            pool.shutdownNow();
        }
        Progress.finish(verbosity);

        Map<String, String> finalMap = new LinkedHashMap<>();
        if (prior != null) finalMap.putAll(prior);
        for (int i = 0; i < newLines.length(); i++) {
            String s = newLines.get(i);
            if (s == null) continue;
            int sep = s.indexOf("  ");
            finalMap.put(s.substring(sep + 2), s.substring(0, sep));
        }

        synchronized (finalizeLock) {
            writeManifestAtomic(db, finalMap);
            Files.deleteIfExists(partial);
            completed.set(true);
        }
        try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) {}

        if (verbosity != Verbosity.QUIET) {
            System.out.println("Wrote " + finalMap.size() + " hashes to " + db.getFileName());
        }
    }

    private static class Progress {
        final int total;
        final Verbosity v;
        final int baseOffset;
        int count = 0;
        Progress(int total, Verbosity v) { this(total, v, 0); }
        Progress(int total, Verbosity v, int baseOffset) {
            this.total = total;
            this.v = v;
            this.baseOffset = baseOffset;
        }
        void tick(String rel) {
            if (v == Verbosity.QUIET) return;
            synchronized (System.out) {
                int n = ++count + baseOffset;
                if (v == Verbosity.VERBOSE) {
                    System.out.printf("  [%d/%d] %s%n", n, total, rel);
                } else {
                    double pct = total == 0 ? 100.0 : (100.0 * n / total);
                    System.out.printf("\r  [%d/%d] %5.1f%%   ", n, total, pct);
                    System.out.flush();
                }
            }
        }
        static void finish(Verbosity v) {
            if (v == Verbosity.NORMAL) {
                synchronized (System.out) {
                    System.out.println();
                }
            }
        }
    }

    static class VerifyResult {
        LinkedHashMap<String, String> expected;
        List<String> mismatched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> extras = new ArrayList<>();
        int ok;
    }

    static VerifyResult runVerify(Path root, Path db, int threads, List<String> ignores, Verbosity verbosity) throws Exception {
        List<String> lines = Files.readAllLines(db, StandardCharsets.UTF_8);
        LinkedHashMap<String, String> expected = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            int sep = line.indexOf("  ");
            if (sep < 0) continue;
            expected.put(line.substring(sep + 2), line.substring(0, sep));
        }

        int total = expected.size();
        if (verbosity != Verbosity.QUIET) {
            System.out.println("Verifying " + total + " files with " + threads + (threads == 1 ? " thread..." : " threads..."));
        }

        String[] entries = expected.keySet().toArray(new String[0]);
        String[] result = new String[total];
        Progress prog = new Progress(total, verbosity);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>(total);
            for (int idx = 0; idx < total; idx++) {
                final int slot = idx;
                final String rel = entries[idx];
                final String want = expected.get(rel);
                futures.add(pool.submit(() -> {
                    Path p = root.resolve(rel);
                    String status;
                    if (!Files.exists(p)) {
                        status = "MISSING";
                    } else {
                        String got = sha256(p);
                        status = got.equalsIgnoreCase(want) ? "OK" : "MISMATCH";
                    }
                    result[slot] = status;
                    prog.tick(rel);
                    return null;
                }));
            }
            awaitAll(futures);
        } finally {
            pool.shutdownNow();
        }
        Progress.finish(verbosity);

        VerifyResult r = new VerifyResult();
        r.expected = expected;
        for (int i = 0; i < total; i++) {
            switch (result[i]) {
                case "OK"       -> r.ok++;
                case "MISMATCH" -> r.mismatched.add(entries[i]);
                case "MISSING"  -> r.missing.add(entries[i]);
            }
        }

        List<Path> present = collectFiles(root, ignores);
        Set<String> expectedKeys = expected.keySet();
        for (Path p : present) {
            String rel = toRelative(root, p);
            if (!expectedKeys.contains(rel)) r.extras.add(rel);
        }
        return r;
    }

    private static void printSummary(VerifyResult r) {
        boolean failed = !r.mismatched.isEmpty() || !r.missing.isEmpty();
        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println("  OK         : " + r.ok);
        System.out.println("  Mismatched : " + r.mismatched.size());
        System.out.println("  Missing    : " + r.missing.size());
        System.out.println("  New/Extra  : " + r.extras.size());
        System.out.println();
        if (failed) {
            System.out.println("Status: INTEGRITY CHECK FAILED");
            if (!r.mismatched.isEmpty()) {
                System.out.println("  - " + r.mismatched.size()
                        + " file(s) MISMATCH — bytes differ from the recorded hash (possible corruption).");
            }
            if (!r.missing.isEmpty()) {
                System.out.println("  - " + r.missing.size()
                        + " file(s) MISSING — listed in manifest but not found on disk.");
            }
            if (!r.extras.isEmpty()) {
                System.out.println("  Note: " + r.extras.size()
                        + " NEW file(s) on disk are NOT a failure — use [u] update to add them to the manifest.");
            }
        } else {
            System.out.println("Status: All files intact.");
            if (!r.extras.isEmpty()) {
                System.out.println("  Note: " + r.extras.size()
                        + " NEW file(s) on disk are not in the manifest — use [u] update to add them.");
            }
        }
    }

    private static void verify(Path root, Path db, int threads, List<String> ignores, Verbosity verbosity, BufferedReader in) throws Exception {
        VerifyResult r = runVerify(root, db, threads, ignores, verbosity);
        printSummary(r);
        drillVerify(in, r, verbosity);
        if (!r.mismatched.isEmpty() || !r.missing.isEmpty()) {
            System.exit(1);
        }
    }

    private static void drillVerify(BufferedReader in, VerifyResult r, Verbosity v) throws IOException {
        if (v == Verbosity.QUIET) return;
        if (r.mismatched.isEmpty() && r.missing.isEmpty() && r.extras.isEmpty()) return;
        while (true) {
            System.out.println();
            System.out.println("View details?");
            System.out.println("  [1] Show MISMATCH entries (" + r.mismatched.size() + ")");
            System.out.println("  [2] Show MISSING entries  (" + r.missing.size() + ")");
            System.out.println("  [3] Show NEW entries      (" + r.extras.size() + ")");
            System.out.println("  [q] done");
            System.out.print("Choice: ");
            String line = in.readLine();
            if (line == null) return;
            String c = line.trim().toLowerCase(Locale.ROOT);
            if (c.equals("q") || c.isEmpty()) return;
            switch (c) {
                case "1" -> dumpList("MISMATCH", r.mismatched);
                case "2" -> dumpList("MISSING ", r.missing);
                case "3" -> dumpList("NEW     ", r.extras);
                default  -> System.out.println("Unknown choice: " + line);
            }
        }
    }

    private static void dumpList(String label, List<String> items) {
        if (items.isEmpty()) { System.out.println("(none)"); return; }
        for (String s : items) System.out.println("  " + label + ": " + s);
    }

    private static void drillUpdate(BufferedReader in, Verbosity v, List<String> added, List<String> removed, List<String> mismatched) throws IOException {
        if (v == Verbosity.QUIET) return;
        if (added.isEmpty() && removed.isEmpty() && mismatched.isEmpty()) return;
        while (true) {
            System.out.println();
            System.out.println("View details?");
            System.out.println("  [1] Show ADDED entries      (" + added.size() + ")");
            System.out.println("  [2] Show REMOVED entries    (" + removed.size() + ")");
            System.out.println("  [3] Show MISMATCH entries   (" + mismatched.size() + ") — left untouched");
            System.out.println("  [q] done");
            System.out.print("Choice: ");
            String line = in.readLine();
            if (line == null) return;
            String c = line.trim().toLowerCase(Locale.ROOT);
            if (c.equals("q") || c.isEmpty()) return;
            switch (c) {
                case "1" -> dumpList("ADDED   ", added);
                case "2" -> dumpList("REMOVED ", removed);
                case "3" -> dumpList("MISMATCH", mismatched);
                default  -> System.out.println("Unknown choice: " + line);
            }
        }
    }

    private static void update(Path root, Path db, int threads, List<String> ignores, Verbosity verbosity, BufferedReader in) throws Exception {
        VerifyResult r = runVerify(root, db, threads, ignores, verbosity);
        printSummary(r);

        if (!r.mismatched.isEmpty()) {
            System.out.println();
            System.out.println("Mismatched files will NOT be updated automatically — that's potential corruption.");
            System.out.println("Resolve them manually (restore from backup, or use [r] rehash) before updating.");
        }

        if (r.missing.isEmpty() && r.extras.isEmpty()) {
            System.out.println();
            System.out.println("Manifest is already in sync. Nothing to update.");
            return;
        }

        boolean removeMissing = false;
        boolean addExtras = false;

        if (!r.missing.isEmpty()) {
            System.out.println();
            removeMissing = confirm(in, "Remove " + r.missing.size() + " missing entries from the manifest? [y/N]: ");
        }
        if (!r.extras.isEmpty()) {
            System.out.println();
            addExtras = confirm(in, "Hash and add " + r.extras.size() + " new files to the manifest? [y/N]: ");
        }

        if (!removeMissing && !addExtras) {
            System.out.println("No changes applied.");
            return;
        }

        LinkedHashMap<String, String> updated = new LinkedHashMap<>(r.expected);
        if (removeMissing) for (String m : r.missing) updated.remove(m);

        if (addExtras) {
            int total = r.extras.size();
            if (verbosity != Verbosity.QUIET) {
                System.out.println("Hashing " + total + " new files with " + threads + (threads == 1 ? " thread..." : " threads..."));
            }
            String[] digests = new String[total];
            Progress prog = new Progress(total, verbosity);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> futures = new ArrayList<>(total);
                for (int idx = 0; idx < total; idx++) {
                    final int slot = idx;
                    final String rel = r.extras.get(idx);
                    futures.add(pool.submit(() -> {
                        digests[slot] = sha256(root.resolve(rel));
                        prog.tick(rel);
                        return null;
                    }));
                }
                awaitAll(futures);
            } finally {
                pool.shutdownNow();
            }
            Progress.finish(verbosity);
            for (int i = 0; i < total; i++) updated.put(r.extras.get(i), digests[i]);
        }

        Path backup = backupDb(db);
        System.out.println("Backed up existing manifest to " + backup.getFileName());
        writeManifestAtomic(db, updated);

        int added = addExtras ? r.extras.size() : 0;
        int removed = removeMissing ? r.missing.size() : 0;
        System.out.println();
        System.out.println("=== Update summary ===");
        System.out.println("  Added       : " + added + " new file(s) hashed and recorded");
        System.out.println("  Removed     : " + removed + " missing entry/entries dropped from manifest");
        System.out.println("  Unchanged   : " + r.ok + " file(s) already matched");
        System.out.println("  Mismatched  : " + r.mismatched.size() + " file(s) left untouched (possible corruption)");
        System.out.println("  Total in db : " + updated.size());
        System.out.println("  Backup      : " + backup.getFileName());
        if (!r.mismatched.isEmpty()) {
            System.out.println();
            System.out.println("WARNING: " + r.mismatched.size()
                    + " mismatched file(s) were NOT updated. Investigate before trusting the manifest.");
        }

        List<String> addedList = addExtras ? r.extras : Collections.emptyList();
        List<String> removedList = removeMissing ? r.missing : Collections.emptyList();
        drillUpdate(in, verbosity, addedList, removedList, r.mismatched);
    }

    private static void diffManifests(Path a, Path b) throws IOException {
        if (!Files.exists(a)) { System.err.println("Not found: " + a); System.exit(2); }
        if (!Files.exists(b)) { System.err.println("Not found: " + b); System.exit(2); }
        DiffResult r = computeDiff(loadManifest(a), loadManifest(b));

        System.out.println("Diff: " + a + " -> " + b);
        System.out.println("Added   : " + r.added.size());
        System.out.println("Removed : " + r.removed.size());
        System.out.println("Changed : " + r.changed.size());
        for (String s : r.added)   System.out.println("  + " + s);
        for (String s : r.removed) System.out.println("  - " + s);
        for (String s : r.changed) System.out.println("  ~ " + s);
        if (!r.isEmpty()) System.exit(1);
    }

    private static void awaitAll(List<Future<?>> futures) throws Exception {
        ExecutionException firstFailure = null;
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                if (firstFailure == null) firstFailure = e;
            }
        }
        if (firstFailure != null) {
            Throwable cause = firstFailure.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
    }

    static List<Path> collectFiles(Path root, List<String> ignores) throws IOException {
        List<PathMatcher> matchers = compileIgnoreMatchers(ignores);
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
             .filter(p -> !isManifestFile(DB_DIR, DB_FILENAME, PARTIAL_SUFFIX, p))
             .filter(p -> !isIgnored(toRelative(root, p), matchers))
             .sorted()
             .forEach(out::add);
        }
        return out;
    }

}
