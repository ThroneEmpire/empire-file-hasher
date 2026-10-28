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
  - `u` — update: verify, then add new files and drop missing entries,
  - `r` — rehash everything and overwrite the manifest,
  - `q` — quit.
- **Compare two manifests** with `--diff OLD.db NEW.db` to see what
  was added, removed, or changed between snapshots — no rehashing needed.
- **Ignore paths** via `-i/--ignore` or a `.hashignore` file at the root,
  with full glob support (`**/.hist/`, `*.tmp`, `build-*/`).
- Walks recursively through any depth of subdirectories. Hashes any file
  type — videos, archives, ISOs, photos, documents, whatever.
- Streams files in 64 KiB chunks, so terabyte-scale archives are fine.
- Manifest format matches `sha256sum`: `<hex>  <relative/path>` per line, so
  you can also verify with standard Unix tools. Manifests written by
  `sha256sum` itself are read too — text mode (`hash  file`), binary mode
  (`sha256sum -b`, `hash *file`) and BSD style (`SHA256 (file) = hash`):

  ```
  sha256sum -c hashes.db
  ```

## Requirements

- Java 21 or newer on `PATH`.
- No runtime dependencies — just the JDK. (JUnit is pulled in for tests only.)

## Build

```
./gradlew build
```

This produces a runnable jar at:

```
build/libs/empire-file-hasher.jar
```

## Tests

```
./gradlew test
```

The pure helper functions live in `Util` (hashing, manifest I/O, ignore-glob
matching, manifest diffing, db-path resolution) and are unit-tested in
`UtilTest`. `CollectFilesTest` covers the filesystem walk in `Main.collectFiles`
— manifest-family exclusion and glob ignores against a temp directory.

## Usage

```
empire-file-hasher [DIR] [options]
empire-file-hasher --diff OLD.db NEW.db
```

- `DIR` — directory to hash. Defaults to the current working directory.
- `-d NAME` / `--db NAME` — where to keep the manifest. Defaults to
  `hashes.db` inside `DIR`. A **bare name** (`mydb.db`) lives in `DIR`; a
  **path** (`./config/mydb.db` or absolute) is resolved relative to your
  current working directory, so the manifest can live outside the hashed
  tree entirely. Use this when a directory needs more than one manifest,
  or when `hashes.db` is already taken. See [Custom manifest name](#custom-manifest-name).
- `-t N` / `--threads N` — number of hashing threads. Defaults to `1`.
  Increase for fast storage (NVMe / SSD) to overlap I/O with hashing.
  Leave at `1` for spinning disks or network mounts, where seek contention
  usually makes parallel hashing *slower*.
- `-i PATH` / `--ignore PATH` — skip a path or glob. Repeatable. If it
  matches a directory, everything beneath it is skipped too (with or
  without a trailing `/`). See [Ignoring files](#ignoring-files) below.
- `--ignore-file F` — read ignore patterns from `F` instead of the default
  `DIR/.hashignore`. Resolved relative to your current working directory.
  If you point at a file that doesn't exist, that's an error.
- `--no-backup` — don't back up the manifest before update or rehash
  writes a new one. Existing backups are left alone.
- `--keep-backups N` — keep only the newest `N` backups (`N` ≥ 1,
  counting `hashes.db.bak`); older timestamped ones are deleted after each
  new backup. Default: keep every backup. Can't be combined with
  `--no-backup`. See [Rehashing](#rehashing) for how backups are named.
- `-q` / `--quiet` — suppress per-file output and the progress line.
  Final report and warnings still print. Combine with the exit code for
  cron / CI use.
- `-v` / `--verbose` — print every file as it's processed (the old default
  behavior). Useful when piping to a log.
- `--diff OLD.db NEW.db` — compare two manifest files and print added /
  removed / changed paths. See [Diffing manifests](#diffing-manifests).

By default (no `-q`/`-v`), hashing and verification print a single
self-updating progress line `[N/total] XX.X%` instead of one line per
file — much friendlier for archives with tens of thousands of entries.

**Exit codes**: `0` on success, `1` on integrity failure (verify or update
found mismatched, missing, or unreadable files; hashing couldn't read some
paths; or `--diff` found differences), `2` on argument errors (including an
invalid ignore glob).

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
No manifest found at /path/to/archive/hashes.db.
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
=== Summary ===
  OK         : 1234
  Mismatched : 2
  Missing    : 1
  Unreadable : 0
  New/Extra  : 47

Status: INTEGRITY CHECK FAILED
  - 2 file(s) MISMATCH — bytes differ from the recorded hash (possible corruption).
  - 1 file(s) MISSING — listed in manifest but not found on disk.
  Note: 47 NEW file(s) on disk are NOT a failure — use [u] update to add them to the manifest.

View details?
  [1] Show MISMATCH entries   (2)
  [2] Show MISSING entries    (1)
  [3] Show NEW entries        (47)
  [4] Show UNREADABLE entries (0)
  [q] done
Choice:
```

The report classifies every path into five states:
- **OK** — file present, hash matches.
- **MISMATCH** — file present, bytes changed since the manifest was written.
- **MISSING** — file listed in manifest but not found on disk.
- **UNREADABLE** — file or folder exists but couldn't be read (permission
  denied, I/O error, replaced by a directory), so its integrity is unknown.
- **NEW** — file on disk that wasn't in the manifest (added after hashing).

Two more things are reported when they happen:
- **BAD LINE** — a manifest line that couldn't be parsed. Those entries
  can't be checked, so this is a failure (exit `1`), and update refuses
  to rewrite the manifest until the lines are fixed or removed.
- **RENAMED** — on Windows/macOS (case-insensitive), a file renamed only
  in letter case (`Photo.JPG` → `photo.jpg`). Not a failure; update
  records the new name and keeps the hash.

**MISMATCH**, **MISSING**, **UNREADABLE** and **BAD LINE** cause an integrity failure (exit `1`).
**NEW** files are reported but do not fail the check — they're new content
the manifest hasn't been told about yet. Use update mode to add them.

The drill menu after the summary lets you inspect each category on
demand, so you don't have to scroll through thousands of NEW lines just
to find out what failed. Press `q` (or Enter) when done. The menu is shown
whenever any category has entries, and exits cleanly if stdin is closed
(safe for cron / piped invocations).

In `--quiet` mode there's nobody to answer a menu, so instead the
MISMATCH, MISSING and UNREADABLE paths are printed right after the
summary (NEW files are only counted). With `-v`, each file's result is
shown as it is checked (`[12/40] MISMATCH photos/a.jpg`).

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

Resuming only hashes files not already in the partial, plus any file
modified after the partial was saved (so an edit made between runs isn't
recorded with its old hash). Files that were in
the partial but no longer exist on disk (or are now ignored) are dropped
(you'll see a "Dropped N partial entries" line if any).

If hashing stops because of an unexpected error rather than Ctrl-C, the
finished work is saved to the partial the same way and the real error is
printed.

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
    --ignore "**/.cache/" \
    --ignore "*.tmp" \
    -i secrets.txt
```

**Always quote glob patterns** on the CLI — single (`'**/.cache/'`) or
double (`"**/.cache/"`) both work. Without quotes, your shell will
expand the pattern against files in your *current* directory before the
program ever sees it — not against the archive being hashed. Literal
paths (no wildcards) don't need quoting. If you're using lots of
patterns, putting them in `.hashignore` is easier — no shell involved.

**2. `.hashignore` file** at the root of the directory being hashed (or a
file of your choice via `--ignore-file PATH`) — one entry per line. Blank
lines and lines starting with `#` are ignored:

```
# build artifacts
build/
node_modules/

# any .cache folder, at any depth in the tree
**/.cache/

# all .tmp files anywhere
**/*.tmp

# local-only files
secrets.txt
scratch/
```

Matching uses Java's standard glob syntax:
- `*` matches any sequence of characters within a single path component.
- `**` matches across path components (any depth, including zero).
- `?` matches a single character; `[abc]` and `[a-z]` are character classes.
- An entry that matches a directory skips that directory and everything
  under it. A trailing `/` is optional — `build` and `build/` behave the
  same. Ignored directories aren't even opened, so an unreadable ignored
  folder causes no warnings.
- A line starting with `!` **re-includes** paths an earlier line ignored,
  like `.gitignore`. Lines apply in order and the last match wins:
  ```
  build/
  !build/release-notes.txt
  ```
  To ignore a name that really starts with `!`, write `\!name`.
- An invalid glob (e.g. an unclosed `{`) is an error (exit `2`).
- Patterns without `**` are anchored to the root — `build/` only matches
  a top-level `build/`. To match at any depth, use `**/build/`.
- Leading `./` and `/` are stripped; backslashes are normalized to `/`,
  so Windows-style entries work.

Ignores apply to fresh hashing and to verification's "new/extra" detection.
Entries already recorded in `hashes.db` are still verified against disk —
if you want them dropped, rehash after adding the ignore.

Patterns from `.hashignore` (or `--ignore-file`) and any `--ignore` flags
are merged together — you can use both at once. A custom `--ignore-file`
is read relative to your current working directory and must exist; the
default `DIR/.hashignore` is simply skipped if absent.

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

The previous manifest is backed up to `hashes.db.bak` before the new one
is written (see [Rehashing](#rehashing) for how older backups are kept).

Update exits `1` if problems remain afterwards — mismatched or unreadable
files, new files that couldn't be read, or missing entries you chose to
keep — so it can be used from cron.

After the write, update prints an `=== Update summary ===` block (added
/ removed / unchanged / mismatched counts + backup filename). It then
shows a details menu — `[1]` ADDED, `[2]` REMOVED, `[3]` MISMATCH,
`[4]` UNREADABLE, `[5]` MISSING (kept in manifest), `[6]` NEW (not added)
— so you can confirm exactly which paths were touched and which weren't.
The menu also appears when there was nothing to sync or you declined
every change, so you can always see which files are mismatched.

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

### Custom manifest name

By default the manifest is `hashes.db`. Pass `-d` / `--db` to use a
different name inside the same directory:

```
empire-file-hasher /path/to/archive --db photos.db
```

This is useful when:
- a directory needs more than one independent manifest (e.g. one per
  subset of files), or
- the name `hashes.db` is already taken by an unrelated file (for example
  a manifest that belongs to a *different* directory but happens to live
  here).

The custom name drives the whole manifest family consistently:
`photos.db`, `photos.db.partial` (interrupted run), `photos.db.bak*`
(backups), and `photos.db.tmp` (atomic write staging). The whole family
lives **next to the db**, and only files in that family (in that location)
are auto-excluded from hashing.

#### Putting the manifest in another folder

`--db` also accepts a path, letting the manifest live outside the hashed
tree (handy for keeping `.db` files together, or out of the data set):

```
# manifest at ./config/photos.db (relative to where you run the command)
empire-file-hasher /path/to/archive --db ./config/photos.db

# or an absolute path
empire-file-hasher /path/to/archive --db /var/manifests/photos.db
```

Path resolution rules:
- A **bare name** (`photos.db`) → inside `DIR` (the hashed directory).
- A **path** (`./config/photos.db`, `../m.db`, `/abs/path.db`) → resolved
  relative to your **current working directory**, *not* `DIR`.
- The target directory must already exist; `--db` won't create folders.
- The `.partial`, `.bak*`, and `.tmp` files always sit next to the db.

If the db happens to land *inside* the hashed tree (e.g.
`DIR/config/photos.db`), its family is still auto-excluded, so the
manifest never hashes itself.

**Important — foreign manifest files are treated as ordinary data.** The
tool only auto-excludes *its own* manifest family (the one matching the
`--db` name in use). If another manifest such as `hashes.db` or
`hashes.db.partial` from a different directory is sitting in the folder,
it will be hashed and recorded like any other file. To leave it out, add
it to your ignores:

```
empire-file-hasher /path/to/archive --db photos.db \
    --ignore hashes.db --ignore "hashes.db.*"
```

or put the equivalent lines in `.hashignore`.

A partial from a *different* manifest is never mistaken for yours: the
resume prompt only triggers on `<your-db-name>.partial`. So running with
`--db photos.db` will not try to resume from a stray `hashes.db.partial`
— that file is just treated as ordinary data (and hashed unless ignored,
per above).

### Rehashing

Choosing `r` at the prompt overwrites `hashes.db`, so it's guarded by an
extra "are you sure?" confirmation. Before the new manifest is written,
the old one is copied to `hashes.db.bak`, so `.bak` is always the most
recent backup. If a `.bak` already exists, it is first renamed with the
time it was written (`hashes.db.bak.20260422-172334`, plus `-1`, `-2` …
if needed) so previous snapshots are never lost.

By default every backup is kept. To limit the pile-up:

```
# keep just the latest backup (hashes.db.bak)
empire-file-hasher /path/to/archive --keep-backups 1

# keep the 5 most recent
empire-file-hasher /path/to/archive --keep-backups 5

# skip backing up entirely for this run
empire-file-hasher /path/to/archive --no-backup
```

`--keep-backups` only deletes files named exactly like the backups this
tool creates (`hashes.db.bak.YYYYMMDD-HHMMSS[-n]`), so something like a
hand-made `hashes.db.bak.old` is never touched.


### Running from cron or scripts

The menus read answers from stdin, so you can pipe them in:

```
echo v | java -jar empire-file-hasher.jar /path/to/archive -q
```

An invalid answer re-asks instead of quitting, and if input runs out
before a choice is made the program exits `2` without doing anything —
so a script answering the wrong question (for example when a leftover
`.partial` triggers the resume prompt first) fails loudly instead of
reporting success.

Cron, systemd and Docker often run without a UTF-8 locale, in which Java
can't read non-ASCII file names. The tool detects this and relaunches
itself with `LC_ALL=C.UTF-8`; if that locale doesn't exist on the system
it exits `2` and asks you to set a UTF-8 locale yourself. File names that
aren't valid UTF-8 at all can't be stored in the manifest and are reported
as unreadable.

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
- **Symlinks are skipped** (not followed, not hashed) — both file and
  directory links. If your archive contains symlinks you want preserved,
  resolve them to real files first.
- **Unreadable files and folders don't stop the run.** Hashing skips them,
  lists them in a warning, and exits `1`; verify reports them as
  UNREADABLE. Common culprits are `lost+found` on Linux drives and
  `System Volume Information` on Windows drives — ignore them if expected.
- File names containing a backslash or line break are escaped in the
  manifest exactly like `sha256sum` does (the line starts with `\`), so
  `sha256sum -c` still works.
- The manifest is fsynced before it replaces the old one, so a power
  loss can't leave behind an empty `hashes.db`.
- **Empty folders are not tracked.** The manifest records files only, so
  an empty directory that later disappears will go unnoticed. Files inside
  a deleted folder are still reported individually as `MISSING`.
