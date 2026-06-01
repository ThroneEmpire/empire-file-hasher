package com.empire.hasher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static com.empire.hasher.Util.normalizeIgnores;
import static com.empire.hasher.Util.toRelative;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style tests for {@link Main#collectFiles}, which walks the
 * filesystem and applies manifest-family exclusion + ignore patterns. These
 * depend on the package-private {@code DB_DIR}/{@code DB_FILENAME} statics, so
 * each test configures them explicitly.
 */
class CollectFilesTest {

    @BeforeEach
    void resetConfig() {
        Main.DB_FILENAME = "hashes.db";
        Main.DB_DIR = null;
    }

    private static void write(Path p, String content) throws Exception {
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> relativeNames(Path root, List<Path> files) {
        return files.stream().map(p -> toRelative(root, p)).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void excludesOwnManifestFamilyButHashesForeignManifest(@TempDir Path root) throws Exception {
        write(root.resolve("a.txt"), "a");
        write(root.resolve("sub/b.txt"), "b");
        write(root.resolve("hashes.db"), "self");            // our manifest
        write(root.resolve("hashes.db.partial"), "x");       // our partial
        write(root.resolve("hashes.db.bak"), "x");           // our backup
        write(root.resolve("other.db"), "foreign");          // unrelated manifest

        Main.DB_FILENAME = "hashes.db";
        Main.DB_DIR = root;

        Set<String> got = relativeNames(root, Main.collectFiles(root, List.of()));
        assertEquals(new TreeSet<>(Set.of("a.txt", "sub/b.txt", "other.db")), got,
                "own manifest family excluded; foreign manifest treated as data");
    }

    @Test
    void manifestInSubdirExcludesItself(@TempDir Path root) throws Exception {
        write(root.resolve("a.txt"), "a");
        write(root.resolve("config/my.db"), "self");
        write(root.resolve("config/my.db.partial"), "x");
        write(root.resolve("config/keep.txt"), "k");

        Main.DB_FILENAME = "my.db";
        Main.DB_DIR = root.resolve("config");

        Set<String> got = relativeNames(root, Main.collectFiles(root, List.of()));
        assertEquals(new TreeSet<>(Set.of("a.txt", "config/keep.txt")), got);
    }

    @Test
    void appliesGlobIgnores(@TempDir Path root) throws Exception {
        write(root.resolve("keep.txt"), "k");
        write(root.resolve("notes.tmp"), "t");
        write(root.resolve("deep/a/.cache/x"), "c");
        write(root.resolve("deep/a/real.txt"), "r");

        Main.DB_FILENAME = "hashes.db";
        Main.DB_DIR = root;

        List<String> ignores = normalizeIgnores(List.of("**/*.tmp", "**/.cache/"));
        Set<String> got = relativeNames(root, Main.collectFiles(root, ignores));
        assertEquals(new TreeSet<>(Set.of("keep.txt", "deep/a/real.txt")), got);
    }
}
