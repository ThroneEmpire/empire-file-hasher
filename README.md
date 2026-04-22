# empire-file-hasher

A tiny Java utility for long-term file preservation.

Point it at a directory and it will recursively SHA-256 every file underneath,
store the digests in a `hashes.db` manifest, and later verify that nothing
has changed — bitrot, silent corruption, accidental edits, a bad copy, a
failing drive. If a byte flips, this will tell you.

## What it does

- **First run** (no `hashes.db` present): asks whether you want to hash
  everything under the current directory and writes `hashes.db`.
- **Later runs** (manifest exists): prompts you to
  - `v` — verify every file in the manifest against its stored hash,
  - `r` — rehash everything and overwrite the manifest,
  - `q` — quit.
- Walks recursively through any depth of subdirectories. Hashes any file
  type — videos, archives, ISOs, photos, documents, whatever.
- Streams files in 64 KiB chunks, so terabyte-scale archives are fine.
- Manifest format matches `sha256sum`: `<hex>  <relative/path>` per line, so
  you can also verify with standard Unix tools:

  ```
  sha256sum -c hashes.db
  ```

## Requirements

- Java 21 or newer on `PATH`.
- No external dependencies — just the JDK.

## Build

```
./gradlew build
```

This produces a runnable jar at:

```
build/libs/empire-file-hasher.jar
```

## Usage

By default the program operates on **the directory you launch it from**.
Optionally pass a directory as the first argument to point it elsewhere.

```
# hash the current directory
cd /path/to/archive
java -jar /path/to/empire-file-hasher/build/libs/empire-file-hasher.jar

# hash a specific directory
java -jar /path/to/empire-file-hasher/build/libs/empire-file-hasher.jar /path/to/archive
```

First run:

```
No hashes.db found in this directory.
Do you want to hash every file here (recursively) and create one? [y/N]: y
  [1/3] movies/trip-2025.mkv
  [2/3] photos/raw/IMG_0001.CR2
  [3/3] notes.txt
Wrote 3 hashes to hashes.db
```

Later, to check nothing has rotted:

```
hashes.db exists.
  [v] verify existing files against hashes.db
  [r] rehash everything and overwrite hashes.db
  [q] quit
Choice: v
...
=== Verification report ===
OK         : 3
Mismatched : 0
Missing    : 0
New/Extra  : 0
All files intact.
```

The report flags four states per file:
- **OK** — file present, hash matches.
- **MISMATCH** — file present, bytes changed since the manifest was written.
- **MISSING** — file listed in manifest but not found on disk.
- **NEW** — file on disk that wasn't in the manifest (added after hashing).


## Notes

- `hashes.db` itself is excluded from hashing and verification.
- Paths in the manifest are stored relative to the root directory and always
  use forward slashes, so a manifest produced on Windows will verify on
  Linux and vice-versa.
- The manifest is plain UTF-8 text — safe to diff, grep, commit, copy to
  another medium alongside the archive. Despite the `.db` extension, it is
  **not** SQLite; it's just the same line format `sha256sum` emits.
- **Symlinks are skipped** (not followed, not hashed). If your archive
  contains symlinks you want preserved, resolve them to real files first.
- **Empty folders are not tracked.** The manifest records files only, so
  an empty directory that later disappears will go unnoticed. Files inside
  a deleted folder are still reported individually as `MISSING`.
