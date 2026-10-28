package com.empire.hasher;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

import static com.empire.hasher.Util.loadManifest;
import static com.empire.hasher.Util.sha256;
import static org.junit.jupiter.api.Assertions.*;

/** Integration tests for {@link Main#hashAll} and {@link Main#runVerify}. */
class HashVerifyTest {

    @BeforeEach
    void resetConfig() {
        Main.DB_FILENAME = "hashes.db";
        Main.DB_DIR = null;
    }

    private static void write(Path p, String content) throws Exception {
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Point the manifest-family statics at the given db. */
    private static void configDb(Path db) {
        Main.DB_FILENAME = db.getFileName().toString();
        Main.DB_DIR = db.getParent();
    }

    // --- hashAll -----------------------------------------------------------

    @Test
    void hashAllWritesCorrectManifest(@TempDir Path root) throws Exception {
        write(root.resolve("a.txt"), "alpha");
        write(root.resolve("sub/b.txt"), "bravo");
        Path db = root.resolve("hashes.db");
        configDb(db);

        Main.hashAll(root, db, 1, null, List.of(), Main.Verbosity.QUIET);

        Map<String, String> m = loadManifest(db);
        assertEquals(2, m.size());
        assertEquals(sha256(root.resolve("a.txt")), m.get("a.txt"));
        assertEquals(sha256(root.resolve("sub/b.txt")), m.get("sub/b.txt"));
        assertFalse(Files.exists(root.resolve("hashes.db.partial")), "no partial on success");
    }

    @Test
    void multiThreadedHashingMatchesSingleThreaded(@TempDir Path root) throws Exception {
        for (int i = 0; i < 50; i++) write(root.resolve("f" + i + ".bin"), "content-" + i);
        Path db1 = root.resolve("one.db");
        Path db8 = root.resolve("eight.db");

        configDb(db1);
        Main.hashAll(root, db1, 1, null, List.of("one.db", "eight.db"), Main.Verbosity.QUIET);
        configDb(db8);
        Main.hashAll(root, db8, 8, null, List.of("one.db", "eight.db"), Main.Verbosity.QUIET);

        assertEquals(loadManifest(db1), loadManifest(db8), "thread count must not affect output");
        assertEquals(50, loadManifest(db1).size());
    }

    @Test
    void resumePreservesPriorEntriesAndAddsNewOnes(@TempDir Path root) throws Exception {
        write(root.resolve("done.txt"), "already");
        write(root.resolve("todo.txt"), "remaining");
        Path db = root.resolve("hashes.db");
        configDb(db);

        // Prior holds a deliberately bogus hash for done.txt. If resume works,
        // it is carried over verbatim (done.txt is NOT re-hashed).
        Map<String, String> prior = new LinkedHashMap<>();
        prior.put("done.txt", "BOGUSHASH");

        Main.hashAll(root, db, 1, prior, List.of(), Main.Verbosity.QUIET);

        Map<String, String> m = loadManifest(db);
        assertEquals("BOGUSHASH", m.get("done.txt"), "prior entry preserved, not recomputed");
        assertEquals(sha256(root.resolve("todo.txt")), m.get("todo.txt"));
        assertEquals(2, m.size());
    }

    @Test
    void hashAllRespectsIgnores(@TempDir Path root) throws Exception {
        write(root.resolve("keep.txt"), "k");
        write(root.resolve("skip.tmp"), "s");
        Path db = root.resolve("hashes.db");
        configDb(db);

        Main.hashAll(root, db, 2, null, Util.normalizeIgnores(List.of("**/*.tmp")), Main.Verbosity.QUIET);

        Map<String, String> m = loadManifest(db);
        assertEquals(Set.of("keep.txt"), m.keySet());
    }

    // --- runVerify ---------------------------------------------------------

    private Main.VerifyResult hashThenVerify(Path root, Path db, int threads) throws Exception {
        configDb(db);
        Main.hashAll(root, db, threads, null, List.of(), Main.Verbosity.QUIET);
        return Main.runVerify(root, db, threads, List.of(), Main.Verbosity.QUIET);
    }

    @Test
    void verifyAllOk(@TempDir Path root) throws Exception {
        write(root.resolve("a.txt"), "a");
        write(root.resolve("b.txt"), "b");
        Path db = root.resolve("hashes.db");

        Main.VerifyResult r = hashThenVerify(root, db, 4);
        assertEquals(2, r.ok);
        assertTrue(r.mismatched.isEmpty());
        assertTrue(r.missing.isEmpty());
        assertTrue(r.extras.isEmpty());
    }

    @Test
    void verifyDetectsMismatchMissingAndExtra(@TempDir Path root) throws Exception {
        write(root.resolve("ok.txt"), "ok");
        write(root.resolve("changed.txt"), "original");
        write(root.resolve("deleted.txt"), "bye");
        Path db = root.resolve("hashes.db");
        configDb(db);
        Main.hashAll(root, db, 1, null, List.of(), Main.Verbosity.QUIET);

        // Mutate the tree after hashing.
        write(root.resolve("changed.txt"), "MUTATED");
        Files.delete(root.resolve("deleted.txt"));
        write(root.resolve("brand-new.txt"), "fresh");

        Main.VerifyResult r = Main.runVerify(root, db, 4, List.of(), Main.Verbosity.QUIET);
        assertEquals(1, r.ok, "only ok.txt is intact");
        assertEquals(List.of("changed.txt"), r.mismatched);
        assertEquals(List.of("deleted.txt"), r.missing);
        assertEquals(List.of("brand-new.txt"), r.extras);
    }

    @Test
    void verifyIgnoredFilesAreNotReportedAsExtra(@TempDir Path root) throws Exception {
        write(root.resolve("a.txt"), "a");
        Path db = root.resolve("hashes.db");
        configDb(db);
        Main.hashAll(root, db, 1, null, List.of(), Main.Verbosity.QUIET);

        write(root.resolve("scratch.tmp"), "junk"); // added after, but ignored
        Main.VerifyResult r = Main.runVerify(root, db, 1,
                Util.normalizeIgnores(List.of("**/*.tmp")), Main.Verbosity.QUIET);
        assertTrue(r.extras.isEmpty(), "ignored files must not count as new/extra");
        assertEquals(1, r.ok);
    }

    // --- backupDb ----------------------------------------------------------

    @Test
    void backupKeepsNewestAsBakAndRetiresOlderWithTimestamp(@TempDir Path root) throws Exception {
        Path db = root.resolve("hashes.db");
        write(db, "v1");
        Main.DB_FILENAME = "hashes.db";
        Main.DB_DIR = root;

        Path first = Main.backupDb(db);
        assertEquals("hashes.db.bak", first.getFileName().toString());
        assertEquals("v1", Files.readString(first));

        // Second and third backups (same second): .bak is always the newest,
        // and older ones are retired under distinct timestamped names.
        write(db, "v2");
        assertEquals(first, Main.backupDb(db));
        write(db, "v3");
        assertEquals(first, Main.backupDb(db));
        assertEquals("v3", Files.readString(first));

        List<String> retired = new ArrayList<>();
        try (var s = Files.list(root)) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("hashes.db.bak.")).forEach(retired::add);
        }
        assertEquals(2, retired.size(), "no backup clobbered: " + retired);
        Set<String> contents = new HashSet<>();
        for (String n : retired) contents.add(Files.readString(root.resolve(n)));
        assertEquals(Set.of("v1", "v2"), contents);
    }

    @Test
    void pruneBackupsKeepsOnlyNewestN(@TempDir Path root) throws Exception {
        Path db = root.resolve("hashes.db");
        Main.DB_FILENAME = "hashes.db";
        Main.DB_DIR = root;
        write(root.resolve("hashes.db.bak.old"), "user's own file"); // not ours to delete

        for (String v : List.of("v1", "v2", "v3", "v4")) {
            write(db, v);
            Main.backupDb(db);
            Main.pruneBackups(db, 2);
        }

        Set<String> backups = new TreeSet<>();
        Map<String, String> contents = new HashMap<>();
        try (var s = Files.list(root)) {
            s.filter(p -> p.getFileName().toString().startsWith("hashes.db.bak")).forEach(p -> {
                backups.add(p.getFileName().toString());
                try { contents.put(p.getFileName().toString(), Files.readString(p)); } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        assertEquals(3, backups.size(), "newest 2 backups plus the untouched user file: " + backups);
        assertEquals("v4", contents.get("hashes.db.bak"));
        assertEquals("user's own file", contents.get("hashes.db.bak.old"));
        assertEquals(Set.of("v4", "v3", "user's own file"), new HashSet<>(contents.values()));
    }

    @Test
    void pruneBackupsWithKeepOneLeavesOnlyBak(@TempDir Path root) throws Exception {
        Path db = root.resolve("hashes.db");
        Main.DB_FILENAME = "hashes.db";
        Main.DB_DIR = root;
        for (String v : List.of("v1", "v2", "v3")) {
            write(db, v);
            Main.backupDb(db);
        }
        assertEquals(2, Main.pruneBackups(db, 1).size());
        try (var s = Files.list(root)) {
            assertEquals(List.of("hashes.db", "hashes.db.bak"),
                    s.map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertEquals("v3", Files.readString(root.resolve("hashes.db.bak")));
    }

    // --- unreadable files & resume ----------------------------------------

    @Test
    void verifyReportsUnreadableEntryInsteadOfAborting(@TempDir Path root) throws Exception {
        write(root.resolve("a.txt"), "a");
        write(root.resolve("b.txt"), "b");
        Path db = root.resolve("hashes.db");
        configDb(db);
        Main.hashAll(root, db, 1, null, List.of(), Main.Verbosity.QUIET);

        // Replace b.txt with a directory: it exists but can't be hashed.
        Files.delete(root.resolve("b.txt"));
        Files.createDirectory(root.resolve("b.txt"));

        Main.VerifyResult r = Main.runVerify(root, db, 2, List.of(), Main.Verbosity.QUIET);
        assertEquals(1, r.ok);
        assertEquals(Set.of("b.txt"), r.unreadable.keySet());
        assertTrue(r.missing.isEmpty());
        assertTrue(r.failed());
    }

    @Test
    void verifyAcceptsSha256sumBinaryModeManifest(@TempDir Path root) throws Exception {
        write(root.resolve("f1.txt"), "x");
        write(root.resolve("f2.txt"), "y");
        Path db = root.resolve("hashes.db");
        configDb(db);
        Files.write(db, List.of(sha256(root.resolve("f1.txt")) + " *f1.txt",
                sha256(root.resolve("f2.txt")) + " *f2.txt"), StandardCharsets.UTF_8);

        Main.VerifyResult r = Main.runVerify(root, db, 1, List.of(), Main.Verbosity.QUIET);
        assertEquals(2, r.ok);
        assertTrue(r.extras.isEmpty());
        assertFalse(r.failed());
    }

    @Test
    void verifyFailsOnUnparseableManifestLines(@TempDir Path root) throws Exception {
        write(root.resolve("a.txt"), "a");
        Path db = root.resolve("hashes.db");
        configDb(db);
        Files.write(db, List.of(sha256(root.resolve("a.txt")) + "  a.txt", "this line is damaged"),
                StandardCharsets.UTF_8);

        Main.VerifyResult r = Main.runVerify(root, db, 1, List.of(), Main.Verbosity.QUIET);
        assertEquals(1, r.ok);
        assertEquals(1, r.malformed.size());
        assertTrue(r.failed(), "a damaged manifest must not pass as intact");
    }

    @Test
    void resumeRehashesFilesChangedAfterPartialWasSaved(@TempDir Path root) throws Exception {
        write(root.resolve("same.txt"), "s");
        write(root.resolve("edited.txt"), "e");
        FileTime savedAt = FileTime.fromMillis(System.currentTimeMillis() - 60_000);
        Files.setLastModifiedTime(root.resolve("same.txt"), FileTime.fromMillis(savedAt.toMillis() - 60_000));
        Files.setLastModifiedTime(root.resolve("edited.txt"), FileTime.fromMillis(savedAt.toMillis() + 30_000));

        Map<String, String> prior = new LinkedHashMap<>();
        prior.put("same.txt", "OLD-SAME");
        prior.put("edited.txt", "OLD-EDITED");
        assertEquals(1, Main.dropChangedSince(root, prior, savedAt));
        assertEquals(Set.of("same.txt"), prior.keySet());
    }

    @Test
    void caseOnlyRenameIsReportedAsRenamedNotNew(@TempDir Path root) throws Exception {
        write(root.resolve("Photo.JPG"), "x");
        Assumptions.assumeTrue(Files.exists(root.resolve("photo.jpg")),
                "only meaningful on case-insensitive filesystems (Windows, macOS)");
        Path db = root.resolve("hashes.db");
        configDb(db);
        Main.hashAll(root, db, 1, null, List.of(), Main.Verbosity.QUIET);
        Files.move(root.resolve("Photo.JPG"), root.resolve("photo.jpg"));

        Main.VerifyResult r = Main.runVerify(root, db, 1, List.of(), Main.Verbosity.QUIET);
        assertEquals(Map.of("Photo.JPG", "photo.jpg"), r.renamed);
        assertTrue(r.extras.isEmpty(), "the new spelling must not also show up as NEW");
        assertFalse(r.failed());
    }

    @Test
    void hashAllSkipsUnreadableDirectoryAndReportsIt(@TempDir Path root) throws Exception {
        write(root.resolve("ok.txt"), "ok");
        Path locked = root.resolve("locked");
        write(locked.resolve("secret.txt"), "s");
        Path db = root.resolve("hashes.db");
        configDb(db);
        try {
            Files.setPosixFilePermissions(locked, Set.of());
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("POSIX permissions not supported");
        }
        try {
            Assumptions.assumeFalse(Files.isReadable(locked), "running as root; permissions not enforced");
            int unreadable = Main.hashAll(root, db, 1, null, List.of(), Main.Verbosity.QUIET);
            assertEquals(1, unreadable);
            assertEquals(Set.of("ok.txt"), loadManifest(db).keySet());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void resumeDropsPriorEntriesThatAreNowIgnored(@TempDir Path root) throws Exception {
        write(root.resolve("keep.txt"), "k");
        write(root.resolve("junk.tmp"), "j");
        Path db = root.resolve("hashes.db");
        configDb(db);

        Map<String, String> prior = new LinkedHashMap<>();
        prior.put("keep.txt", sha256(root.resolve("keep.txt")));
        prior.put("junk.tmp", sha256(root.resolve("junk.tmp")));

        Main.hashAll(root, db, 1, prior, Util.normalizeIgnores(List.of("*.tmp")), Main.Verbosity.QUIET);
        assertEquals(Set.of("keep.txt"), loadManifest(db).keySet());
    }
}
