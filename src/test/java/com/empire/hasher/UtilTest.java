package com.empire.hasher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static com.empire.hasher.Util.*;
import static org.junit.jupiter.api.Assertions.*;

class UtilTest {

    // --- sha256 ------------------------------------------------------------

    @Test
    void sha256MatchesKnownVector(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("hello.txt");
        // "hello\n" — same content used in the manual smoke tests.
        Files.write(f, "hello\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(
                "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03",
                sha256(f));
    }

    @Test
    void sha256OfEmptyFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("empty");
        Files.createFile(f);
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                sha256(f));
    }

    // --- toRelative --------------------------------------------------------

    @Test
    void toRelativeUsesForwardSlashes(@TempDir Path dir) {
        Path p = dir.resolve("sub").resolve("file.txt");
        assertEquals("sub/file.txt", toRelative(dir, p));
    }

    // --- manifest round-trip ----------------------------------------------

    @Test
    void manifestRoundTripAndSorting(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("hashes.db");
        Map<String, String> in = new LinkedHashMap<>();
        in.put("z/last.txt", "aaa");
        in.put("a/first.txt", "bbb");
        writeManifestAtomic(db, in);

        // File should be sorted by path on disk.
        List<String> lines = Files.readAllLines(db, StandardCharsets.UTF_8);
        assertEquals(List.of("bbb  a/first.txt", "aaa  z/last.txt"), lines);

        // Loading gives back the same key/value pairs.
        Map<String, String> out = loadManifest(db);
        assertEquals("bbb", out.get("a/first.txt"));
        assertEquals("aaa", out.get("z/last.txt"));
        assertEquals(2, out.size());
    }

    @Test
    void writeManifestAtomicLeavesNoTmpFile(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("hashes.db");
        writeManifestAtomic(db, Map.of("a.txt", "deadbeef"));
        assertFalse(Files.exists(dir.resolve("hashes.db.tmp")), "tmp staging file should be gone");
        assertTrue(Files.exists(db));
    }

    @Test
    void loadManifestSkipsBlankAndMalformedLines(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("hashes.db");
        Files.write(db, List.of(
                "abc  good.txt",
                "",
                "no-double-space-here.txt",
                "def  also/good.txt"), StandardCharsets.UTF_8);
        Map<String, String> out = loadManifest(db);
        assertEquals(2, out.size());
        assertEquals("abc", out.get("good.txt"));
        assertEquals("def", out.get("also/good.txt"));
    }

    // --- normalizeIgnores --------------------------------------------------

    @Test
    void normalizeStripsDotSlashAndKeepsTrailingSlash() {
        List<String> n = normalizeIgnores(List.of("./build/", "  secrets.txt  ", "/abs/", "", "   "));
        assertEquals(List.of("build/", "secrets.txt", "abs/"), n);
    }

    @Test
    void normalizeConvertsBackslashes() {
        assertEquals(List.of("a/b/c"), normalizeIgnores(List.of("a\\b\\c")));
    }

    // --- ignore matching ---------------------------------------------------

    private static boolean ignored(String rel, String... patterns) {
        List<PathMatcher> m = compileIgnoreMatchers(normalizeIgnores(Arrays.asList(patterns)));
        return isIgnored(rel, m);
    }

    @Test
    void dirPatternAnchoredToRoot() {
        assertTrue(ignored("build/x.o", "build/"));
        assertTrue(ignored("build", "build/"));           // the dir entry itself
        assertFalse(ignored("sub/build/x.o", "build/"));  // not at any depth without **
    }

    @Test
    void doubleStarMatchesAnyDepth() {
        assertTrue(ignored(".cache/x", "**/.cache/"));
        assertTrue(ignored("a/b/.cache/x", "**/.cache/"));
        assertTrue(ignored(".cache", "**/.cache/"));      // zero-depth via ** matching empty
    }

    @Test
    void starMatchesWithinComponentOnly() {
        assertTrue(ignored("notes.tmp", "*.tmp"));
        assertFalse(ignored("sub/notes.tmp", "*.tmp"));   // * doesn't cross a slash
        assertTrue(ignored("sub/notes.tmp", "**/*.tmp")); // ** does
    }

    @Test
    void literalFileMatch() {
        assertTrue(ignored("secrets.txt", "secrets.txt"));
        assertFalse(ignored("secrets.txt.bak", "secrets.txt"));
    }

    @Test
    void questionMarkMatchesSingleChar() {
        assertTrue(ignored("a.txt", "?.txt"));
        assertFalse(ignored("ab.txt", "?.txt"));
    }

    @Test
    void characterClassMatches() {
        assertTrue(ignored("file1.log", "file[0-9].log"));
        assertTrue(ignored("fileb.log", "file[abc].log"));
        assertFalse(ignored("filez.log", "file[abc].log"));
    }

    @Test
    void doubleStarMidPatternMatchesNestedDir() {
        assertTrue(ignored("a/x/y/b.txt", "a/**/b.txt"));
        assertTrue(ignored("a/x/b.txt", "a/**/b.txt"));
        // Note: Java glob requires at least one level for a mid-path "**", so
        // "a/**/b.txt" does NOT match "a/b.txt". (Leading "**/" is special-cased
        // in compileIgnoreMatchers; mid-path is not.)
        assertFalse(ignored("a/b.txt", "a/**/b.txt"));
    }

    @Test
    void emptyMatchersIgnoreNothing() {
        assertFalse(isIgnored("anything", List.of()));
        assertFalse(isIgnored("anything", null));
    }

    // --- isManifestFile ----------------------------------------------------

    @Test
    void manifestFamilyExcludedInDbDir(@TempDir Path dir) {
        String name = "hashes.db";
        assertTrue(isManifestFile(dir, name, ".partial", dir.resolve("hashes.db")));
        assertTrue(isManifestFile(dir, name, ".partial", dir.resolve("hashes.db.partial")));
        assertTrue(isManifestFile(dir, name, ".partial", dir.resolve("hashes.db.tmp")));
        assertTrue(isManifestFile(dir, name, ".partial", dir.resolve("hashes.db.bak")));
        assertTrue(isManifestFile(dir, name, ".partial", dir.resolve("hashes.db.bak.20260101-000000")));
    }

    @Test
    void nonManifestFilesNotExcluded(@TempDir Path dir) {
        String name = "hashes.db";
        assertFalse(isManifestFile(dir, name, ".partial", dir.resolve("photo.jpg")));
        // A foreign manifest with a different name is ordinary data.
        assertFalse(isManifestFile(dir, name, ".partial", dir.resolve("other.db")));
        // Same name but in a different directory is not ours.
        assertFalse(isManifestFile(dir, name, ".partial", dir.resolve("sub").resolve("hashes.db")));
    }

    // --- resolveDbPath -----------------------------------------------------

    @Test
    void bareNameResolvesInsideRoot(@TempDir Path root) {
        assertEquals(root.resolve("my.db"), resolveDbPath(root, "my.db", "hashes.db"));
    }

    @Test
    void nullArgUsesDefaultInsideRoot(@TempDir Path root) {
        assertEquals(root.resolve("hashes.db"), resolveDbPath(root, null, "hashes.db"));
    }

    @Test
    void pathArgResolvesAgainstCwdNotRoot(@TempDir Path root) {
        Path got = resolveDbPath(root, "./config/my.db", "hashes.db");
        Path expected = Paths.get("./config/my.db").toAbsolutePath().normalize();
        assertEquals(expected, got);
        assertFalse(got.startsWith(root), "path-style --db should not be under the hashed root");
    }

    // --- computeDiff -------------------------------------------------------

    @Test
    void diffDetectsAddedRemovedChanged() {
        Map<String, String> oldM = new LinkedHashMap<>();
        oldM.put("keep.txt", "h1");
        oldM.put("gone.txt", "h2");
        oldM.put("edit.txt", "h3");

        Map<String, String> newM = new LinkedHashMap<>();
        newM.put("keep.txt", "h1");        // unchanged
        newM.put("edit.txt", "h3-NEW");    // changed
        newM.put("fresh.txt", "h4");       // added

        DiffResult d = computeDiff(oldM, newM);
        assertEquals(List.of("fresh.txt"), d.added);
        assertEquals(List.of("gone.txt"), d.removed);
        assertEquals(List.of("edit.txt"), d.changed);
        assertFalse(d.isEmpty());
    }

    @Test
    void diffIsCaseInsensitiveOnHashAndEmptyWhenIdentical() {
        Map<String, String> a = Map.of("f.txt", "ABCDEF");
        Map<String, String> b = Map.of("f.txt", "abcdef");
        DiffResult d = computeDiff(a, b);
        assertTrue(d.isEmpty(), "hex hashes should compare case-insensitively");
    }
}
