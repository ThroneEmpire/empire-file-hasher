package com.empire.hasher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

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
        ensureUtf8FileNames();
        String dirArg = null;
        int threads = 1;
        List<String> ignoreArgs = new ArrayList<>();
        Verbosity verbosity = Verbosity.NORMAL;
        String diffA = null, diffB = null;
        String dbArg = null;
        String ignoreFileArg = null;
        boolean noBackup = false;
        int keepBackups = -1; // -1 = keep every backup
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
            } else if (a.equals("--no-backup")) {
                noBackup = true;
            } else if (a.equals("--keep-backups")) {
                if (i + 1 >= args.length) {
                    System.err.println("Missing value for " + a);
                    System.exit(2);
                }
                try {
                    keepBackups = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid backup count: " + args[i]);
                    System.exit(2);
                }
                if (keepBackups < 1) {
                    System.err.println("--keep-backups must be >= 1 (use --no-backup to skip backups)");
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
                System.out.println("  -i, --ignore     path or glob to ignore (repeatable). Matching a directory");
                System.out.println("                   skips everything beneath it.");
                System.out.println("  --ignore-file F  read ignore patterns from F (default: .hashignore in DIR).");
                System.out.println("  --no-backup      don't back up the manifest before update/rehash");
                System.out.println("  --keep-backups N keep only the newest N manifest backups (default: all)");
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

        if (noBackup && keepBackups > 0) {
            System.err.println("--no-backup and --keep-backups can't be used together.");
            System.exit(2);
        }
        if (noBackup) keepBackups = 0;

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
        if (Files.isDirectory(db)) {
            System.err.println("Manifest path is a directory, not a file: " + db);
            System.exit(2);
        }
        boolean dbIsPath = dbArg != null && (dbArg.contains("/") || dbArg.contains("\\"));
        if (dbIsPath) {
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
        try {
            compileIgnoreMatchers(normalizedIgnores);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        }

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
                Manifest partialManifest = readManifest(partial);
                Map<String, String> prior = partialManifest.entries();
                System.out.println("Found a partial hash from a previous interrupted run: "
                        + partial.getFileName() + " (" + prior.size() + " entries)");
                if (!partialManifest.malformed().isEmpty()) {
                    System.out.println("  (" + partialManifest.malformed().size()
                            + " damaged line(s) in it will be ignored; those files get rehashed)");
                }
                System.out.println("  [r] resume — continue hashing the remaining files");
                System.out.println("  [d] discard the partial and start over");
                System.out.println("  [q] quit");
                switch (choose(in, "r", "d", "q")) {
                    case "r" -> {
                        int changed = dropChangedSince(root, prior, Files.getLastModifiedTime(partial));
                        if (changed > 0) {
                            System.out.println(changed + " file(s) changed since the partial was saved and will be rehashed.");
                        }
                        if (hashAll(root, db, threads, prior, normalizedIgnores, verbosity) > 0) System.exit(1);
                        return;
                    }
                    case "d" -> {
                        Files.delete(partial);
                        System.out.println("Partial discarded.");
                    }
                    default -> { System.out.println("Exiting."); return; }
                }
            }

            if (!Files.exists(db)) {
                System.out.println("No manifest found at " + db + ".");
                if (confirm(in, "Do you want to hash every file here (recursively) and create one? [y/N]: ")) {
                    if (hashAll(root, db, threads, null, normalizedIgnores, verbosity) > 0) System.exit(1);
                } else {
                    System.out.println("Nothing to do. Exiting.");
                }
            } else {
                System.out.println(DB_FILENAME + " exists.");
                System.out.println("  [v] verify existing files against " + DB_FILENAME);
                System.out.println("  [u] update — verify, then add new files and drop missing entries");
                System.out.println("  [r] rehash everything and overwrite " + DB_FILENAME);
                System.out.println("  [q] quit");
                switch (choose(in, "v", "u", "r", "q")) {
                    case "v" -> { if (verify(root, db, threads, normalizedIgnores, verbosity, in)) System.exit(1); }
                    case "u" -> { if (update(root, db, threads, normalizedIgnores, verbosity, keepBackups, in)) System.exit(1); }
                    case "r" -> {
                        System.out.println();
                        System.out.println("WARNING: rehashing will overwrite the existing " + DB_FILENAME + ".");
                        System.out.println(keepBackups == 0
                                ? "--no-backup is set: the old manifest will NOT be backed up."
                                : "The old manifest will be backed up before the new one is written.");
                        if (confirm(in, "Are you sure you want to rehash everything? [y/N]: ")) {
                            backupAndReport(db, keepBackups);
                            if (hashAll(root, db, threads, null, normalizedIgnores, verbosity) > 0) System.exit(1);
                        } else {
                            System.out.println("Rehash cancelled.");
                        }
                    }
                    default -> System.out.println("Exiting.");
                }
            }
        }
    }

    /**
     * Back up the db per the --no-backup / --keep-backups setting and say
     * what happened. Returns the backup, or null when backups are off.
     */
    private static Path backupAndReport(Path db, int keepBackups) throws IOException {
        if (keepBackups == 0) {
            System.out.println("Skipping manifest backup (--no-backup).");
            return null;
        }
        Path backup = backupDb(db);
        System.out.println("Backed up existing manifest to " + backup.getFileName());
        if (keepBackups > 0) {
            List<Path> pruned = pruneBackups(db, keepBackups);
            if (!pruned.isEmpty()) {
                System.out.println("Deleted " + pruned.size() + " old backup(s) (--keep-backups " + keepBackups + ").");
            }
        }
        return backup;
    }

    /**
     * Delete all but the newest {@code keep} backups, counting
     * {@code DB_FILENAME.bak} as the newest. Only files named like backups
     * this tool creates ({@code .bak.yyyyMMdd-HHmmss[-n]}) are touched.
     * Returns the deleted files.
     */
    static List<Path> pruneBackups(Path db, int keep) throws IOException {
        Path dir = db.toAbsolutePath().getParent();
        List<Path> retired = listRetiredBackups(dir);
        // Newest first: later timestamp, then higher collision suffix.
        retired.sort(Comparator.comparing(Main::retiredSortKey).reversed());

        int keepRetired = Files.exists(dir.resolve(DB_FILENAME + ".bak")) ? keep - 1 : keep;
        List<Path> deleted = new ArrayList<>();
        for (int i = Math.max(keepRetired, 0); i < retired.size(); i++) {
            Files.deleteIfExists(retired.get(i));
            deleted.add(retired.get(i));
        }
        return deleted;
    }

    /**
     * Copy the db to {@code DB_FILENAME.bak}, so {@code .bak} is always the
     * most recent backup. An existing {@code .bak} is first renamed to
     * {@code .bak.<timestamp>}, stamped with when that manifest was written.
     */
    static Path backupDb(Path db) throws IOException {
        Path primary = db.resolveSibling(DB_FILENAME + ".bak");
        if (Files.exists(primary)) {
            String stamp = java.time.LocalDateTime.ofInstant(
                            Files.getLastModifiedTime(primary).toInstant(), java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            // On a same-second collision, number past every existing backup
            // with this stamp (not just the first free slot), so names always
            // sort oldest-to-newest even after pruning freed earlier ones.
            long maxSuffix = -1;
            for (Path p : listRetiredBackups(db.toAbsolutePath().getParent())) {
                var m = RETIRED_BACKUP.matcher(p.getFileName().toString());
                if (m.matches() && m.group(1).equals(DB_FILENAME) && m.group(2).equals(stamp)) {
                    maxSuffix = Math.max(maxSuffix, m.group(3) == null ? 0 : Long.parseLong(m.group(3)));
                }
            }
            String suffix = maxSuffix < 0 ? "" : "-" + (maxSuffix + 1);
            Files.move(primary, db.resolveSibling(DB_FILENAME + ".bak." + stamp + suffix));
        }
        Files.copy(db, primary, StandardCopyOption.COPY_ATTRIBUTES);
        return primary;
    }

    // <db name>.bak.<yyyyMMdd-HHmmss>[-<n>]
    private static final java.util.regex.Pattern RETIRED_BACKUP =
            java.util.regex.Pattern.compile("(.+)\\.bak\\.(\\d{8}-\\d{6})(?:-(\\d+))?");

    /** Timestamped backups of the current DB_FILENAME in {@code dir}. */
    private static List<Path> listRetiredBackups(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                var m = RETIRED_BACKUP.matcher(p.getFileName().toString());
                if (m.matches() && m.group(1).equals(DB_FILENAME)) out.add(p);
            }
        }
        return out;
    }

    private static String retiredSortKey(Path p) {
        var m = RETIRED_BACKUP.matcher(p.getFileName().toString());
        m.matches();
        return m.group(2) + String.format("%019d", m.group(3) == null ? 0 : Long.parseLong(m.group(3)));
    }

    /**
     * Prompt for one of {@code valid} until a valid choice is entered, so a
     * typo (or input meant for a different menu) never silently quits. Exits
     * with code 2 if input ends first, so a script that answered the wrong
     * question doesn't look like a successful run.
     */
    private static String choose(BufferedReader in, String... valid) throws IOException {
        while (true) {
            System.out.print("Choice: ");
            String line = in.readLine();
            if (line == null) {
                System.out.println();
                System.err.println("No choice made (input ended). Exiting without doing anything.");
                System.exit(2);
            }
            String c = line.trim().toLowerCase(Locale.ROOT);
            if (Arrays.asList(valid).contains(c)) return c;
            System.out.println("Unknown choice '" + line.trim() + "' — enter one of: " + String.join(", ", valid));
        }
    }

    /**
     * When resuming, drop partial entries whose file was modified after the
     * partial was saved, so they're rehashed instead of keeping a stale hash.
     * Returns how many were dropped.
     */
    static int dropChangedSince(Path root, Map<String, String> prior, java.nio.file.attribute.FileTime savedAt) {
        int before = prior.size();
        prior.keySet().removeIf(rel -> {
            try {
                return Files.getLastModifiedTime(root.resolve(rel)).compareTo(savedAt) > 0;
            } catch (IOException | InvalidPathException e) {
                return true; // let the rehash report the problem
            }
        });
        return before - prior.size();
    }

    /**
     * Java decodes file names with the OS locale, fixed at JVM startup. With
     * no UTF-8 locale (common under cron, systemd and Docker) non-ASCII names
     * can't be represented, so relaunch once with LC_ALL=C.UTF-8; if that
     * still isn't UTF-8, stop with instructions rather than crash mid-run.
     */
    private static void ensureUtf8FileNames() throws Exception {
        if (!"/".equals(FileSystems.getDefault().getSeparator())) return; // Windows uses UTF-16 file APIs
        String jnu = System.getProperty("sun.jnu.encoding", "");
        if (jnu.equalsIgnoreCase("UTF-8") || jnu.equalsIgnoreCase("UTF8")) return;

        String guard = "EMPIRE_HASHER_UTF8_RELAUNCH";
        ProcessHandle.Info info = ProcessHandle.current().info();
        if (System.getenv(guard) == null && info.command().isPresent() && info.arguments().isPresent()) {
            List<String> cmd = new ArrayList<>();
            cmd.add(info.command().get());
            cmd.addAll(Arrays.asList(info.arguments().get()));
            ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
            pb.environment().put("LC_ALL", "C.UTF-8");
            pb.environment().put(guard, "1");
            Process child = pb.start();
            // Ctrl-C reaches both processes; give the child time to save its
            // partial before this one exits.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { child.waitFor(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            }));
            System.exit(child.waitFor());
        }
        System.err.println("The system locale is not UTF-8 (file name encoding: " + jnu + "), so file names with");
        System.err.println("non-ASCII characters can't be read correctly. Set a UTF-8 locale and run again, e.g.:");
        System.err.println("  LC_ALL=C.UTF-8 java -jar empire-file-hasher.jar ...");
        System.exit(2);
    }

    private static boolean confirm(BufferedReader in, String prompt) throws IOException {
        System.out.print(prompt);
        String line = in.readLine();
        return line != null && line.trim().equalsIgnoreCase("y");
    }

    /**
     * Hash every file under {@code root} into {@code db}, carrying over
     * {@code prior} entries when resuming. Returns the number of paths that
     * could not be read (and so are not in the manifest).
     */
    static int hashAll(Path root, Path db, int threads, Map<String, String> prior, List<String> ignores, Verbosity verbosity) throws Exception {
        Path partial = db.resolveSibling(DB_FILENAME + PARTIAL_SUFFIX);
        Map<String, String> unreadable = new TreeMap<>();
        List<Path> allFiles = collectFiles(root, ignores, unreadable);

        // If resuming, drop any prior entries whose files no longer exist on
        // disk or are now ignored, so the manifest matches what a fresh run
        // would record.
        if (prior != null) {
            Set<String> present = new HashSet<>();
            for (Path p : allFiles) present.add(toRelative(root, p));
            int before = prior.size();
            prior.keySet().removeIf(rel -> !present.contains(rel));
            int dropped = before - prior.size();
            if (dropped > 0) {
                System.out.println("Dropped " + dropped + " partial entries whose files no longer exist or are now ignored.");
            }
        }

        List<String> todo = new ArrayList<>();
        for (Path p : allFiles) {
            String rel = toRelative(root, p);
            if (prior == null || !prior.containsKey(rel)) todo.add(rel);
        }

        int total = todo.size();
        int already = prior == null ? 0 : prior.size();
        if (prior != null) {
            System.out.println("Resuming: " + already + " already hashed, " + total + " remaining.");
        }
        System.out.println("Hashing " + total + " files with " + threads + (threads == 1 ? " thread..." : " threads..."));

        AtomicReferenceArray<String> digests = new AtomicReferenceArray<>(total);
        String[] failures = new String[total];
        AtomicBoolean completed = new AtomicBoolean(false);
        Object finalizeLock = new Object();

        Thread hook = new Thread(() -> {
            synchronized (finalizeLock) {
                if (completed.get()) return;
                try {
                    Map<String, String> snapshot = collectResults(prior, todo, digests);
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

        Map<String, String> finalMap;
        try {
            final Progress prog = new Progress(allFiles.size(), verbosity, already);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> futures = new ArrayList<>(total);
                for (int idx = 0; idx < total; idx++) {
                    final int slot = idx;
                    final String rel = todo.get(idx);
                    futures.add(pool.submit(() -> {
                        try {
                            digests.set(slot, sha256(root.resolve(rel)));
                        } catch (IOException | InvalidPathException e) {
                            failures[slot] = describe(e);
                        }
                        prog.tick(rel, failures[slot] == null ? null : "UNREADABLE");
                        return null;
                    }));
                }
                awaitAll(futures);
            } finally {
                pool.shutdownNow();
            }
            Progress.finish(verbosity);

            finalMap = collectResults(prior, todo, digests);
            synchronized (finalizeLock) {
                writeManifestAtomic(db, finalMap);
                Files.deleteIfExists(partial);
                completed.set(true);
            }
        } catch (Exception e) {
            // Keep whatever finished so the run can be resumed, and report the
            // real failure instead of leaving the shutdown hook to call it an
            // interruption.
            synchronized (finalizeLock) {
                if (!completed.get()) {
                    completed.set(true);
                    try {
                        Map<String, String> snapshot = collectResults(prior, todo, digests);
                        writeManifestAtomic(partial, snapshot);
                        System.err.println();
                        System.err.println("Hashing failed: " + e + ". Saved " + snapshot.size()
                                + " entries to " + partial.getFileName() + ". Run again to resume.");
                    } catch (IOException saveFailure) {
                        e.addSuppressed(saveFailure);
                    }
                }
            }
            throw e;
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) {}
        }

        for (int i = 0; i < total; i++) {
            if (failures[i] != null) unreadable.put(todo.get(i), failures[i]);
        }
        System.out.println("Wrote " + finalMap.size() + " hashes to " + db.getFileName());
        reportUnreadable(unreadable, "were NOT recorded in the manifest");
        return unreadable.size();
    }

    /** Prior entries plus every digest finished so far. */
    private static Map<String, String> collectResults(Map<String, String> prior, List<String> rels,
                                                      AtomicReferenceArray<String> digests) {
        Map<String, String> out = new LinkedHashMap<>();
        if (prior != null) out.putAll(prior);
        for (int i = 0; i < digests.length(); i++) {
            String d = digests.get(i);
            if (d != null) out.put(rels.get(i), d);
        }
        return out;
    }

    private static void reportUnreadable(Map<String, String> unreadable, String consequence) {
        if (unreadable.isEmpty()) return;
        System.out.println();
        System.out.println("WARNING: " + unreadable.size() + " path(s) could not be read and " + consequence + ":");
        for (String s : describeAll(unreadable)) System.out.println("  " + s);
    }

    private static List<String> describeAll(Map<String, String> unreadable) {
        List<String> out = new ArrayList<>(unreadable.size());
        for (Map.Entry<String, String> e : unreadable.entrySet()) out.add(e.getKey() + " (" + e.getValue() + ")");
        return out;
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
        void tick(String rel) { tick(rel, null); }
        /** {@code status}, if given, is shown next to the path in verbose mode. */
        void tick(String rel, String status) {
            if (v == Verbosity.QUIET) return;
            synchronized (System.out) {
                int n = ++count + baseOffset;
                if (v == Verbosity.VERBOSE) {
                    if (status == null) System.out.printf("  [%d/%d] %s%n", n, total, rel);
                    else System.out.printf("  [%d/%d] %-10s %s%n", n, total, status, rel);
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
        Map<String, String> unreadable = new TreeMap<>(); // rel path -> reason
        Map<String, String> renamed = new TreeMap<>();    // manifest name -> on-disk name (case-only rename)
        List<String> malformed = new ArrayList<>();       // manifest lines that couldn't be parsed
        int ok;

        boolean failed() {
            return !mismatched.isEmpty() || !missing.isEmpty() || !unreadable.isEmpty() || !malformed.isEmpty();
        }
    }

    static VerifyResult runVerify(Path root, Path db, int threads, List<String> ignores, Verbosity verbosity) throws Exception {
        Manifest manifest = readManifest(db);
        LinkedHashMap<String, String> expected = new LinkedHashMap<>(manifest.entries());

        int total = expected.size();
        if (verbosity != Verbosity.QUIET) {
            System.out.println("Verifying " + total + " files with " + threads + (threads == 1 ? " thread..." : " threads..."));
        }

        String[] entries = expected.keySet().toArray(new String[0]);
        String[] result = new String[total];
        String[] reasons = new String[total];
        Progress prog = new Progress(total, verbosity);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>(total);
            for (int idx = 0; idx < total; idx++) {
                final int slot = idx;
                final String rel = entries[idx];
                final String want = expected.get(rel);
                futures.add(pool.submit(() -> {
                    String status;
                    try {
                        String got = sha256(root.resolve(rel));
                        status = got.equalsIgnoreCase(want) ? "OK" : "MISMATCH";
                    } catch (NoSuchFileException e) {
                        status = "MISSING";
                    } catch (IOException | InvalidPathException e) {
                        status = "UNREADABLE";
                        reasons[slot] = describe(e);
                    }
                    result[slot] = status;
                    prog.tick(rel, status);
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
        r.malformed.addAll(manifest.malformed());
        for (int i = 0; i < total; i++) {
            switch (result[i]) {
                case "OK"         -> r.ok++;
                case "MISMATCH"   -> r.mismatched.add(entries[i]);
                case "MISSING"    -> r.missing.add(entries[i]);
                case "UNREADABLE" -> r.unreadable.put(entries[i], reasons[i]);
            }
        }

        List<Path> present = collectFiles(root, ignores, r.unreadable);
        Set<String> expectedKeys = expected.keySet();
        Map<String, String> expectedByLowerCase = new HashMap<>();
        for (String k : expectedKeys) expectedByLowerCase.put(k.toLowerCase(Locale.ROOT), k);
        Set<String> presentRels = new HashSet<>();
        for (Path p : present) presentRels.add(toRelative(root, p));
        for (Path p : present) {
            String rel = toRelative(root, p);
            if (expectedKeys.contains(rel)) continue;
            // On case-insensitive filesystems (Windows, macOS) a case-only
            // rename makes the old name still resolve, so it verifies OK and
            // the new spelling looks NEW. Recognise it as the same file.
            String old = expectedByLowerCase.get(rel.toLowerCase(Locale.ROOT));
            if (old != null && !presentRels.contains(old) && isSameFile(root.resolve(old), p)) {
                r.renamed.put(old, rel);
            } else {
                r.extras.add(rel);
            }
        }
        return r;
    }

    private static boolean isSameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    private static void printSummary(VerifyResult r) {
        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println("  OK         : " + r.ok);
        System.out.println("  Mismatched : " + r.mismatched.size());
        System.out.println("  Missing    : " + r.missing.size());
        System.out.println("  Unreadable : " + r.unreadable.size());
        System.out.println("  New/Extra  : " + r.extras.size());
        if (!r.renamed.isEmpty()) System.out.println("  Renamed    : " + r.renamed.size() + " (letter case only)");
        if (!r.malformed.isEmpty()) System.out.println("  Bad lines  : " + r.malformed.size() + " in the manifest");
        System.out.println();
        if (r.failed()) {
            System.out.println("Status: INTEGRITY CHECK FAILED");
            if (!r.mismatched.isEmpty()) {
                System.out.println("  - " + r.mismatched.size()
                        + " file(s) MISMATCH — bytes differ from the recorded hash (possible corruption).");
            }
            if (!r.missing.isEmpty()) {
                System.out.println("  - " + r.missing.size()
                        + " file(s) MISSING — listed in manifest but not found on disk.");
            }
            if (!r.unreadable.isEmpty()) {
                System.out.println("  - " + r.unreadable.size()
                        + " path(s) UNREADABLE — permission or I/O error, integrity could not be checked.");
            }
            if (!r.malformed.isEmpty()) {
                System.out.println("  - " + r.malformed.size()
                        + " manifest line(s) could not be parsed — those entries were NOT checked.");
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
        if (!r.renamed.isEmpty()) {
            System.out.println("  Note: " + r.renamed.size()
                    + " file(s) were renamed (letter case only) — use [u] update to record the new names.");
        }
    }

    private static List<String> describeRenames(Map<String, String> renamed) {
        List<String> out = new ArrayList<>(renamed.size());
        for (Map.Entry<String, String> e : renamed.entrySet()) out.add(e.getKey() + " -> " + e.getValue());
        return out;
    }

    /** Returns true if the integrity check failed. */
    private static boolean verify(Path root, Path db, int threads, List<String> ignores, Verbosity verbosity, BufferedReader in) throws Exception {
        VerifyResult r = runVerify(root, db, threads, ignores, verbosity);
        printSummary(r);
        drillMenu(in, verbosity, List.of(
                new Section("MISMATCH", r.mismatched, null, true),
                new Section("MISSING", r.missing, null, true),
                new Section("NEW", r.extras, null, false),
                new Section("UNREADABLE", describeAll(r.unreadable), null, true),
                new Section("RENAMED", describeRenames(r.renamed), "letter case only", false),
                new Section("BAD LINE", r.malformed, "in the manifest", true)));
        return r.failed();
    }

    /** One category in a "View details?" menu. */
    record Section(String name, List<String> items, String note, boolean printWhenQuiet) {}

    /**
     * The "View details?" menu, shown whenever any section has entries —
     * whatever path verify/update took to get here. In quiet mode nobody is
     * there to answer, so sections marked printWhenQuiet are printed instead.
     */
    private static void drillMenu(BufferedReader in, Verbosity v, List<Section> sections) throws IOException {
        if (sections.stream().allMatch(s -> s.items().isEmpty())) return;
        int width = sections.stream().mapToInt(s -> s.name().length()).max().orElse(0);
        if (v == Verbosity.QUIET) {
            for (Section s : sections) {
                if (s.printWhenQuiet() && !s.items().isEmpty()) {
                    System.out.println();
                    dumpList(String.format("%-" + width + "s", s.name()), s.items());
                }
            }
            return;
        }
        while (true) {
            System.out.println();
            System.out.println("View details?");
            for (int i = 0; i < sections.size(); i++) {
                Section s = sections.get(i);
                String note = s.note() == null ? "" : " — " + s.note();
                System.out.printf("  [%d] Show %-" + (width + 8) + "s (%d)%s%n",
                        i + 1, s.name() + " entries", s.items().size(), note);
            }
            System.out.println("  [q] done");
            System.out.print("Choice: ");
            String line = in.readLine();
            if (line == null) return;
            String c = line.trim().toLowerCase(Locale.ROOT);
            if (c.equals("q") || c.isEmpty()) return;
            int pick;
            try {
                pick = Integer.parseInt(c);
            } catch (NumberFormatException e) {
                pick = -1;
            }
            if (pick < 1 || pick > sections.size()) {
                System.out.println("Unknown choice: " + line);
                continue;
            }
            Section s = sections.get(pick - 1);
            dumpList(String.format("%-" + width + "s", s.name()), s.items());
        }
    }

    private static void dumpList(String label, List<String> items) {
        if (items.isEmpty()) { System.out.println("(none)"); return; }
        for (String s : items) System.out.println("  " + label + ": " + s);
    }

    /**
     * Returns true if problems remain after the update: mismatched or
     * unreadable files, unparseable manifest lines, or missing entries the
     * user chose to keep.
     */
    private static boolean update(Path root, Path db, int threads, List<String> ignores, Verbosity verbosity,
                                  int keepBackups, BufferedReader in) throws Exception {
        VerifyResult r = runVerify(root, db, threads, ignores, verbosity);
        printSummary(r);

        if (!r.mismatched.isEmpty() || !r.unreadable.isEmpty()) {
            System.out.println();
            System.out.println("Mismatched or unreadable files will NOT be updated automatically — that's potential corruption.");
            System.out.println("Resolve them manually (restore from backup, fix permissions, or use [r] rehash) before updating.");
        }

        boolean removeMissing = false;
        boolean addExtras = false;
        // Rewriting would silently drop lines we couldn't parse, so refuse.
        boolean canWrite = r.malformed.isEmpty();
        if (!canWrite) {
            System.out.println();
            System.out.println("The manifest has " + r.malformed.size() + " line(s) that can't be parsed, so update won't"
                    + " rewrite it (those lines would be lost).");
            System.out.println("Fix or delete them (see BAD LINE below), or use [r] rehash.");
        } else if (r.missing.isEmpty() && r.extras.isEmpty()) {
            if (r.renamed.isEmpty()) {
                System.out.println();
                System.out.println("No missing or new files to sync.");
            }
        } else {
            if (!r.missing.isEmpty()) {
                System.out.println();
                removeMissing = confirm(in, "Remove " + r.missing.size() + " missing entries from the manifest? [y/N]: ");
            }
            if (!r.extras.isEmpty()) {
                System.out.println();
                addExtras = confirm(in, "Hash and add " + r.extras.size() + " new files to the manifest? [y/N]: ");
            }
            if (!removeMissing && !addExtras && r.renamed.isEmpty()) System.out.println("No changes applied.");
        }
        boolean applyRenames = canWrite && !r.renamed.isEmpty();

        List<String> addedList = new ArrayList<>();
        List<String> removedList = removeMissing ? r.missing : Collections.emptyList();
        Map<String, String> addFailed = new TreeMap<>();
        if (removeMissing || addExtras || applyRenames) {
            LinkedHashMap<String, String> updated = new LinkedHashMap<>(r.expected);
            for (String m : removedList) updated.remove(m);
            // Case-only renames keep their recorded hash under the new name.
            for (Map.Entry<String, String> e : r.renamed.entrySet()) updated.put(e.getValue(), updated.remove(e.getKey()));

            if (addExtras) {
                int total = r.extras.size();
                if (verbosity != Verbosity.QUIET) {
                    System.out.println("Hashing " + total + " new files with " + threads + (threads == 1 ? " thread..." : " threads..."));
                }
                String[] digests = new String[total];
                String[] failures = new String[total];
                Progress prog = new Progress(total, verbosity);
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                try {
                    List<Future<?>> futures = new ArrayList<>(total);
                    for (int idx = 0; idx < total; idx++) {
                        final int slot = idx;
                        final String rel = r.extras.get(idx);
                        futures.add(pool.submit(() -> {
                            try {
                                digests[slot] = sha256(root.resolve(rel));
                            } catch (IOException e) {
                                failures[slot] = describe(e);
                            }
                            prog.tick(rel, failures[slot] == null ? null : "UNREADABLE");
                            return null;
                        }));
                    }
                    awaitAll(futures);
                } finally {
                    pool.shutdownNow();
                }
                Progress.finish(verbosity);
                for (int i = 0; i < total; i++) {
                    String rel = r.extras.get(i);
                    if (digests[i] != null) {
                        updated.put(rel, digests[i]);
                        addedList.add(rel);
                    } else {
                        addFailed.put(rel, failures[i]);
                    }
                }
            }

            Path backup = backupAndReport(db, keepBackups);
            writeManifestAtomic(db, updated);

            System.out.println();
            System.out.println("=== Update summary ===");
            System.out.println("  Added       : " + addedList.size() + " new file(s) hashed and recorded");
            System.out.println("  Removed     : " + removedList.size() + " missing entry/entries dropped from manifest");
            System.out.println("  Renamed     : " + r.renamed.size() + " entry/entries updated to the new letter case");
            System.out.println("  Unchanged   : " + r.ok + " file(s) already matched");
            System.out.println("  Mismatched  : " + r.mismatched.size() + " file(s) left untouched (possible corruption)");
            System.out.println("  Unreadable  : " + (r.unreadable.size() + addFailed.size()) + " path(s) skipped");
            System.out.println("  Total in db : " + updated.size());
            System.out.println("  Backup      : " + (backup == null ? "none (--no-backup)" : backup.getFileName()));
            if (!r.mismatched.isEmpty()) {
                System.out.println();
                System.out.println("WARNING: " + r.mismatched.size()
                        + " mismatched file(s) were NOT updated. Investigate before trusting the manifest.");
            }
            if (verbosity != Verbosity.QUIET) reportUnreadable(addFailed, "were NOT added to the manifest");
        }

        // Always offer the details menu, including when nothing was synced
        // or the user declined every change.
        List<String> unreadableList = describeAll(r.unreadable);
        unreadableList.addAll(describeAll(addFailed));
        drillMenu(in, verbosity, List.of(
                new Section("ADDED", addedList, null, false),
                new Section("REMOVED", removedList, null, false),
                new Section("MISMATCH", r.mismatched, "left untouched", true),
                new Section("UNREADABLE", unreadableList, "left untouched", true),
                new Section("MISSING", removeMissing ? List.of() : r.missing, "kept in manifest", true),
                new Section("NEW", addExtras ? List.of() : r.extras, "not added", false),
                new Section("RENAMED", describeRenames(r.renamed),
                        applyRenames ? "names updated" : "letter case only", false),
                new Section("BAD LINE", r.malformed, "in the manifest", true)));

        return !r.mismatched.isEmpty() || !r.unreadable.isEmpty() || !addFailed.isEmpty()
                || !r.malformed.isEmpty() || (!removeMissing && !r.missing.isEmpty());
    }

    private static void diffManifests(Path a, Path b) throws IOException {
        if (!Files.exists(a)) { System.err.println("Not found: " + a); System.exit(2); }
        if (!Files.exists(b)) { System.err.println("Not found: " + b); System.exit(2); }
        Manifest ma = readManifest(a), mb = readManifest(b);
        DiffResult r = computeDiff(ma.entries(), mb.entries());

        System.out.println("Diff: " + a + " -> " + b);
        System.out.println("Added   : " + r.added.size());
        System.out.println("Removed : " + r.removed.size());
        System.out.println("Changed : " + r.changed.size());
        for (String s : r.added)   System.out.println("  + " + s);
        for (String s : r.removed) System.out.println("  - " + s);
        for (String s : r.changed) System.out.println("  ~ " + s);
        boolean bad = warnMalformed(a, ma) | warnMalformed(b, mb);
        if (!r.isEmpty() || bad) System.exit(1);
    }

    /** Print a manifest's unparseable lines to stderr; true if there were any. */
    private static boolean warnMalformed(Path p, Manifest m) {
        if (m.malformed().isEmpty()) return false;
        System.err.println("WARNING: " + m.malformed().size() + " line(s) in " + p + " could not be parsed and were skipped:");
        for (String s : m.malformed()) System.err.println("  " + s);
        return true;
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
        return collectFiles(root, ignores, new TreeMap<>());
    }

    /**
     * Walk {@code root} for regular files to hash. Symlinks are not followed
     * or recorded, ignored directories are not descended into, and paths that
     * can't be read are added to {@code unreadable} (rel path -> reason)
     * instead of aborting the walk.
     */
    static List<Path> collectFiles(Path root, List<String> ignores, Map<String, String> unreadable) throws IOException {
        List<IgnoreRule> rules = compileIgnoreMatchers(ignores);
        // With "!pattern" rules, something inside an ignored directory may be
        // re-included, so ignored directories must still be walked.
        boolean canPrune = !hasNegation(rules);
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (canPrune && !dir.equals(root) && isIgnored(toRelative(root, dir), rules)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) {
                // walkFileTree doesn't follow links, so attrs describe the link
                // itself and symlinks are never regular files here.
                if (!attrs.isRegularFile() || isManifestFile(DB_DIR, DB_FILENAME, PARTIAL_SUFFIX, p)) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = toRelative(root, p);
                if (isIgnored(rel, rules)) return FileVisitResult.CONTINUE;
                if (!resolvesBackTo(root, rel, p)) {
                    // e.g. a name that isn't valid UTF-8: recording it would
                    // store a different name that never verifies.
                    unreadable.putIfAbsent(rel, "file name is not valid UTF-8 and can't be recorded");
                } else {
                    out.add(p);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path p, IOException e) {
                String rel = toRelative(root, p);
                if (!isIgnored(rel, rules)) unreadable.putIfAbsent(rel.isEmpty() ? "." : rel, describe(e));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                if (e != null) {
                    String rel = toRelative(root, dir);
                    unreadable.putIfAbsent(rel.isEmpty() ? "." : rel, describe(e));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(null);
        return out;
    }

    private static boolean resolvesBackTo(Path root, String rel, Path p) {
        try {
            return root.resolve(rel).equals(p);
        } catch (InvalidPathException e) {
            return false;
        }
    }

}
