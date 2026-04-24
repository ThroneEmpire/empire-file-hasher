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

```
empire-file-hasher [DIR] [-t N | --threads N]
```

- `DIR` — directory to hash. Defaults to the current working directory.
- `-t N` / `--threads N` — number of hashing threads. Defaults to `1`.
  Increase for fast storage (NVMe / SSD) to overlap I/O with hashing.
  Leave at `1` for spinning disks or network mounts, where seek contention
  usually makes parallel hashing *slower*.

```
# hash the current directory, single-threaded (default)
cd /path/to/archive
java -jar /path/to/empire-file-hasher/build/libs/empire-file-hasher.jar

# hash a specific directory
java -jar /path/to/empire-file-hasher/build/libs/empire-file-hasher.jar /path/to/archive

# hash with 8 threads (good for SSD/NVMe)
java -jar /path/to/empire-file-hasher/build/libs/empire-file-hasher.jar /path/to/archive -t 8
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

### Interrupting a long run

You can safely Ctrl-C at any point during hashing — especially useful for
archives with tens of thousands of files where a full run takes hours.
On SIGINT the program writes whatever has finished to `hashes.db.partial`
and exits cleanly. `hashes.db` itself is only written as a single atomic
step at the end of a successful run, so it can never be half-written.

Next launch detects the partial and prompts:

```
Found a partial hash from a previous interrupted run: hashes.db.partial (4812 entries)
  [r] resume — continue hashing the remaining files
  [d] discard the partial and start over
  [q] quit
```

Resuming only hashes files not already in the partial. Files that were in
the partial but no longer exist on disk are silently dropped (you'll see a
"Dropped N partial entries" line if any).

### Rehashing

Choosing `r` at the prompt overwrites `hashes.db`, so it's guarded by an
extra "are you sure?" confirmation. Before the new manifest is written,
the old one is copied to `hashes.db.bak`. If a `.bak` already exists, the
backup is timestamped (`hashes.db.bak.20260422-172334`) so previous
snapshots are never lost.


## Notes

- `hashes.db` and any `hashes.db.bak*` backups in the root are excluded
  from hashing and verification.
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
