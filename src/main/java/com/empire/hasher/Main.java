package com.empire.hasher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class Main {

    private static final String DB_FILENAME = "hashes.db";
    private static final int BUFFER_SIZE = 1 << 16; // 64 KiB

    public static void main(String[] args) throws Exception {
        String dirArg = null;
        int threads = 1;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-t") || a.equals("--threads")) {
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
            } else if (a.equals("-h") || a.equals("--help")) {
                System.out.println("Usage: empire-file-hasher [DIR] [-t N | --threads N]");
                System.out.println("  DIR          directory to hash (default: current directory)");
                System.out.println("  -t, --threads  number of hashing threads (default: 1)");
                return;
            } else if (dirArg == null) {
                dirArg = a;
            } else {
                System.err.println("Unexpected argument: " + a);
                System.exit(2);
            }
        }

        Path root = (dirArg != null ? Paths.get(dirArg) : Paths.get("")).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("Not a directory: " + root);
            System.exit(2);
        }
        Path db = root.resolve(DB_FILENAME);

        System.out.println("File Hasher");
        System.out.println("Working directory: " + root);
        System.out.println("Hash database:     " + db);
        System.out.println("Threads:           " + threads);
        System.out.println();

        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            if (!Files.exists(db)) {
                System.out.println("No hashes.db found in this directory.");
                if (confirm(in, "Do you want to hash every file here (recursively) and create one? [y/N]: ")) {
                    hashAll(root, db, threads);
                } else {
                    System.out.println("Nothing to do. Exiting.");
                }
            } else {
                System.out.println("hashes.db exists.");
                System.out.println("  [v] verify existing files against hashes.db");
                System.out.println("  [r] rehash everything and overwrite hashes.db");
                System.out.println("  [q] quit");
                System.out.print("Choice: ");
                String choice = in.readLine();
                if (choice == null) return;
                switch (choice.trim().toLowerCase(Locale.ROOT)) {
                    case "v" -> verify(root, db, threads);
                    case "r" -> {
                        System.out.println();
                        System.out.println("WARNING: rehashing will overwrite the existing hashes.db.");
                        System.out.println("The old manifest will be backed up before the new one is written.");
                        if (confirm(in, "Are you sure you want to rehash everything? [y/N]: ")) {
                            Path backup = backupDb(db);
                            System.out.println("Backed up existing manifest to " + backup.getFileName());
                            hashAll(root, db, threads);
                        } else {
                            System.out.println("Rehash cancelled.");
                        }
                    }
                    default -> System.out.println("Exiting.");
                }
            }
        }
    }

    private static Path backupDb(Path db) throws IOException {
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

    private static void hashAll(Path root, Path db, int threads) throws Exception {
        List<Path> files = collectFiles(root);
        int total = files.size();
        System.out.println("Found " + total + " files. Hashing with " + threads + (threads == 1 ? " thread..." : " threads..."));

        String[] lines = new String[total];
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>(total);
            for (int idx = 0; idx < total; idx++) {
                final int slot = idx;
                final Path p = files.get(idx);
                futures.add(pool.submit(() -> {
                    String rel = toRelative(root, p);
                    String digest = sha256(p);
                    lines[slot] = digest + "  " + rel;
                    int n = done.incrementAndGet();
                    synchronized (System.out) {
                        System.out.printf("  [%d/%d] %s%n", n, total, rel);
                    }
                    return null;
                }));
            }
            awaitAll(futures);
        } finally {
            pool.shutdownNow();
        }

        Files.write(db, Arrays.asList(lines), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Wrote " + total + " hashes to " + db.getFileName());
    }

    private static void verify(Path root, Path db, int threads) throws Exception {
        List<String> lines = Files.readAllLines(db, StandardCharsets.UTF_8);
        LinkedHashMap<String, String> expected = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            int sep = line.indexOf("  ");
            if (sep < 0) continue;
            expected.put(line.substring(sep + 2), line.substring(0, sep));
        }

        int total = expected.size();
        System.out.println("Verifying " + total + " files with " + threads + (threads == 1 ? " thread..." : " threads..."));

        String[] entries = expected.keySet().toArray(new String[0]);
        // result[i]: "OK", "MISSING", or "MISMATCH"
        String[] result = new String[total];
        AtomicInteger done = new AtomicInteger();
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
                    int n = done.incrementAndGet();
                    synchronized (System.out) {
                        System.out.printf("  [%d/%d] %s%n", n, total, rel);
                    }
                    return null;
                }));
            }
            awaitAll(futures);
        } finally {
            pool.shutdownNow();
        }

        int ok = 0, mismatched = 0, missing = 0;
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            switch (result[i]) {
                case "OK"       -> ok++;
                case "MISMATCH" -> { mismatched++; problems.add("MISMATCH: " + entries[i]); }
                case "MISSING"  -> { missing++;    problems.add("MISSING : " + entries[i]); }
            }
        }

        // Detect new files that weren't in the db
        List<Path> present = collectFiles(root);
        Set<String> expectedKeys = expected.keySet();
        List<String> extras = new ArrayList<>();
        for (Path p : present) {
            String rel = toRelative(root, p);
            if (!expectedKeys.contains(rel)) extras.add(rel);
        }

        System.out.println();
        System.out.println("=== Verification report ===");
        System.out.println("OK         : " + ok);
        System.out.println("Mismatched : " + mismatched);
        System.out.println("Missing    : " + missing);
        System.out.println("New/Extra  : " + extras.size());
        for (String s : problems) System.out.println("  " + s);
        for (String s : extras)   System.out.println("  NEW     : " + s);

        if (mismatched == 0 && missing == 0) {
            System.out.println("All files intact.");
        } else {
            System.out.println("Integrity check FAILED.");
        }
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

    private static List<Path> collectFiles(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
             .filter(p -> !isManifestFile(root, p))
             .sorted()
             .forEach(out::add);
        }
        return out;
    }

    private static boolean isManifestFile(Path root, Path p) {
        if (!p.getParent().equals(root)) return false;
        String name = p.getFileName().toString();
        return name.equals(DB_FILENAME) || name.startsWith(DB_FILENAME + ".bak");
    }

    private static String toRelative(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private static String sha256(Path p) throws IOException, NoSuchAlgorithmException {
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
}
