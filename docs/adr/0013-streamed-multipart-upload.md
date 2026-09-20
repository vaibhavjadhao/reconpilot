# ADR 0013: Accept settlement files as streamed uploads

**Status:** Accepted
**Date:** 2026-09-20
**Resolves:** D4

## Context

Ingestion was triggered by `POST /api/ingest?path=/some/file`. The server then
read whatever that path named. Convenient on a laptop and unacceptable anywhere
else: an authenticated caller could ask for `/etc/passwd`, the application's own
configuration, or any file the process could read. It also meant the service
could not be deployed at all, since a remote user has no files on the server.

## Decision

`POST /api/ingest` now takes a **multipart upload**. A caller can only ever
supply its own bytes.

**Spring spools to disk, never to memory.**
`spring.servlet.multipart.file-size-threshold=0` forces every upload straight to
a temporary file. The default keeps small uploads in memory, which is right for
an avatar and catastrophic for a 2 GB settlement file -- the heap would be gone
long before any of the streaming code in ADR 0007 ran.

**Uploads are re-staged under our own directory.** Spring deletes its multipart
temp file when the request completes, which is long before the asynchronous
worker reads it; the file would vanish underneath the worker. `FileStagingService`
copies it somewhere with a lifetime we control, and the worker deletes it in a
`finally` block so a failed batch does not leak disk one upload at a time.

**The digest is computed during that copy.** A `DigestInputStream` hashes the
bytes as they are written, so idempotency costs nothing extra. Hashing
afterwards would read a 2 GB file a second time for no reason.

**Client-supplied filenames are sanitised for the filesystem and preserved for
display.** Only the final path component is kept, unsafe characters are
replaced, backslashes are normalised so a Windows-style name behaves the same,
and any remaining `..` is collapsed. The original name is still stored on the
batch row, because that is what the user recognises. Sanitise where it is
dangerous; keep the original where it is useful.

## On the `..` that was not a vulnerability

A test asserted that `safeName` output never contains `..`. It failed on
`..\..\windows\system32.dll`, which on Unix is a single filename, so the `..`
survived -- harmless, since the path components were already gone.

The code was strengthened rather than the test weakened. **"The result never
contains `..`" is far easier to audit than "the `..` left in this one is
harmless in this position on this operating system."** Invariants that need a
paragraph of reasoning to justify are the ones that break when someone changes
the surrounding code.

## Consequences

- The service can be deployed; nothing about ingestion depends on server-local
  files any more.
- Peak memory is still independent of file size: a buffer for the copy, and one
  line at a time for the parse.
- Staged files are the one new thing that can leak disk. The worker's `finally`
  covers normal operation; a process killed mid-batch still leaves one behind,
  which a startup sweep should handle.
- Uploading 2 GB over HTTP is its own problem -- no resume, no progress, and a
  dropped connection loses the whole transfer. Object-store pre-signed uploads
  are the real answer at that size, and are not built here.
