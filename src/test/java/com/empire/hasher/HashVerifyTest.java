package com.empire.hasher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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
    void backupCreatesBakThenTimestampedBak(@TempDir Path root) throws Exception {
        Path db = root.resolve("hashes.db");
        write(db, "v1");
        Main.DB_FILENAME = "hashes.db";
        Main.DB_DIR = root;

        Path first = Main.backupDb(db);
        assertEquals("hashes.db.bak", first.getFileName().toString());
        assertEquals("v1", Files.readString(first));

        // Second backup must not clobber the first — it gets a timestamp.
        write(db, "v2");
        Path second = Main.backupDb(db);
        assertNotEquals(first, second);
        assertTrue(second.getFileName().toString().startsWith("hashes.db.bak."));
        assertEquals("v1", Files.readString(first), "original .bak left intact");
        assertEquals("v2", Files.readString(second));
    }
}
