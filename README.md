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
empire-file-hasher [DIR] [options]
empire-file-hasher --diff OLD.db NEW.db
```

- `DIR` — directory to hash. Defaults to the current working directory.
- `-t N` / `--threads N` — number of hashing threads. Defaults to `1`.
  Increase for fast storage (NVMe / SSD) to overlap I/O with hashing.
  Leave at `1` for spinning disks or network mounts, where seek contention
  usually makes parallel hashing *slower*.
- `-i PATH` / `--ignore PATH` — skip a path. Repeatable. A trailing `/`
  matches a directory subtree; otherwise the entry matches that exact file
  or directory. See [Ignoring files](#ignoring-files) below.
- `-q` / `--quiet` — suppress per-file output and the progress line.
  Final report still prints. Combine with the `update` mode's exit code
  for cron / CI use.
- `-v` / `--verbose` — print every file as it's processed (the old default
  behavior). Useful when piping to a log.
- `--diff OLD.db NEW.db` — compare two manifest files and print added /
  removed / changed paths. See [Diffing manifests](#diffing-manifests).

By default (no `-q`/`-v`), hashing and verification print a single
self-updating progress line `[N/total] XX.X%` instead of one line per
file — much friendlier for archives with tens of thousands of entries.

**Exit codes**: `0` on success, `1` on integrity failure (verify found
mismatches/missing, or `--diff` found differences), `2` on argument errors.

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
  [u] update — verify, then add new files and drop missing entries
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

Ctrl-C is **safe at any point in any mode** — `hashes.db` is written via
an atomic temp-file rename, so it is never left half-written. The
resume-from-partial behavior currently applies to first-time hashing,
rehashing, and resuming — *not* to the "add new files" step of update
mode. Update is still safe to interrupt (the original manifest is
untouched until the very end), but new-file hashes from an interrupted
update run are not preserved.

### Ignoring files

You can exclude paths from hashing and from the "new/extra" check during
verification. Two sources are supported and merged together:

**1. CLI flag** — `-i` / `--ignore`, repeatable:

```
java -jar empire-file-hasher.jar /path/to/archive \
    --ignore ./build/ \
    --ignore node_modules/ \
    -i secrets.txt
```

**2. `.hashignore` file** at the root of the directory being hashed —
one entry per line. Blank lines and lines starting with `#` are ignored:

```
# build artifacts
build/
node_modules/

# local-only files
secrets.txt
scratch/
```

Matching rules (intentionally simple — no globs or wildcards):
- A trailing `/` means "this directory and everything under it".
- Without a trailing `/`, the entry matches that exact relative path. If it
  happens to be a directory, its contents are also ignored.
- Leading `./` and `/` are stripped; backslashes are normalized to `/`,
  so Windows-style entries work.

Ignores apply to fresh hashing and to verification's "new/extra" detection.
Entries already recorded in `hashes.db` are still verified against disk —
if you want them dropped, rehash after adding the ignore.

### Update mode

The `[u]` option is the everyday "I added/removed some files, sync the
manifest" flow. It runs a full verify pass first, then prompts:

- **Add new files?** — hashes any files on disk that aren't in the manifest
  and inserts them.
- **Remove missing entries?** — drops manifest entries for files that no
  longer exist on disk.

**MISMATCH entries are never auto-updated.** If a file's bytes differ from
its recorded hash, that's exactly the kind of corruption this tool exists
to catch — silently overwriting the hash would defeat the point. Resolve
mismatches manually (restore from backup) or use `[r]` rehash if you
intend to declare the current bytes the new truth.

The previous manifest is backed up to `hashes.db.bak` (timestamped if a
backup already exists) before the new one is written.

### Diffing manifests

```
empire-file-hasher --diff hashes.db.bak hashes.db
```

Compares two manifests without re-reading any files — useful for
"what changed between snapshots?" without paying for another full hash
pass. Output:

```
Diff: hashes.db.bak -> hashes.db
Added   : 2
Removed : 1
Changed : 1
  + photos/new.jpg
  + notes/2026-05.md
  - scratch/old.tmp
  ~ docs/spec.pdf
```

`+` added in the new manifest, `-` removed, `~` same path but different
hash. Exits `1` if any differences exist (handy for cron alerts).

### Rehashing

Choosing `r` at the prompt overwrites `hashes.db`, so it's guarded by an
extra "are you sure?" confirmation. Before the new manifest is written,
the old one is copied to `hashes.db.bak`. If a `.bak` already exists, the
backup is timestamped (`hashes.db.bak.20260422-172334`) so previous
snapshots are never lost.


## Notes

- `hashes.db` and any `hashes.db.bak*` backups in the root are excluded
  from hashing and verification. The `.hashignore` file itself is **not**
  auto-excluded — add it to its own ignore list if you don't want it
  hashed alongside your data.
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
