package com.empire.hasher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

public class Main {

    private static final String DB_FILENAME = "hashes.db";
    private static final int BUFFER_SIZE = 1 << 16; // 64 KiB

    public static void main(String[] args) throws Exception {
        Path root = (args.length > 0 ? Paths.get(args[0]) : Paths.get("")).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("Not a directory: " + root);
            System.exit(2);
        }
        Path db = root.resolve(DB_FILENAME);

        System.out.println("File Hasher");
        System.out.println("Working directory: " + root);
        System.out.println("Hash database:     " + db);
        System.out.println();

        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            if (!Files.exists(db)) {
                System.out.println("No hashes.db found in this directory.");
                if (confirm(in, "Do you want to hash every file here (recursively) and create one? [y/N]: ")) {
                    hashAll(root, db);
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
                    case "v" -> verify(root, db);
                    case "r" -> hashAll(root, db);
                    default -> System.out.println("Exiting.");
                }
            }
        }
    }

    private static boolean confirm(BufferedReader in, String prompt) throws IOException {
        System.out.print(prompt);
        String line = in.readLine();
        return line != null && line.trim().equalsIgnoreCase("y");
    }

    private static void hashAll(Path root, Path db) throws IOException, NoSuchAlgorithmException {
        List<Path> files = collectFiles(root, db);
        System.out.println("Found " + files.size() + " files. Hashing...");

        List<String> lines = new ArrayList<>(files.size());
        int i = 0;
        for (Path p : files) {
            i++;
            String rel = toRelative(root, p);
            System.out.printf("  [%d/%d] %s%n", i, files.size(), rel);
            String digest = sha256(p);
            // Format: <hex>  <relative-path>  (two spaces, like sha256sum)
            lines.add(digest + "  " + rel);
        }

        Files.write(db, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Wrote " + lines.size() + " hashes to " + db.getFileName());
    }

    private static void verify(Path root, Path db) throws IOException, NoSuchAlgorithmException {
        List<String> lines = Files.readAllLines(db, StandardCharsets.UTF_8);
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            int sep = line.indexOf("  ");
            if (sep < 0) continue;
            expected.put(line.substring(sep + 2), line.substring(0, sep));
        }

        int ok = 0, mismatched = 0, missing = 0;
        List<String> problems = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, String> e : expected.entrySet()) {
            i++;
            String rel = e.getKey();
            String want = e.getValue();
            Path p = root.resolve(rel);
            System.out.printf("  [%d/%d] %s%n", i, expected.size(), rel);
            if (!Files.exists(p)) {
                missing++;
                problems.add("MISSING : " + rel);
                continue;
            }
            String got = sha256(p);
            if (got.equalsIgnoreCase(want)) {
                ok++;
            } else {
                mismatched++;
                problems.add("MISMATCH: " + rel);
            }
        }

        // Detect new files that weren't in the db
        List<Path> present = collectFiles(root, db);
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

    private static List<Path> collectFiles(Path root, Path db) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
             .filter(p -> !p.equals(db))
             .sorted()
             .forEach(out::add);
        }
        return out;
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
