# Descriptor-copy experiment (#112)

This is a synthetic Linux experiment, not the qBittorrent provider, a deployable helper,
or proof of full acquisition safety. Nothing calls it from the application. Python is not
added to the application image. Existing torrent and ZIP routes are unchanged.

`tools/snapshot_descriptor_prototype.py` accepts trusted, already-open source/cache
directory descriptors and an explicit member list carrying host-captured fingerprints.
These are **preconditions**, not authorization implemented by this experiment. A public
caller or plugin must never supply these capabilities. The current path-only selection API
cannot fulfill them. Cache descriptor owner/mode checks do not validate configured ancestry;
trusted root acquisition remains required. Same-UID malicious processes and root are outside
the proposed boundary.

The prototype uses Linux x86_64/aarch64 `openat2` and fails closed on unsupported syscall,
kernel flags, symlinks, escape or mount crossings. Its behavior follows the
[Linux openat2 documentation](https://man7.org/linux/man-pages/man2/openat2.2.html).
Source descriptor identity is checked against the supplied device/inode/type/size/mtime/ctime
fingerprint, before copy and after a second descriptor read/hash; the destination descriptor
is also re-read and hashed. Source hardlink aliases and
special files are refused. Flat generated output names are exclusively created through an
owned operation directory descriptor. Actual bytes and file counts are bounded.

Twenty deterministic synthetic tests exercise exact copy, real post-first-chunk cancellation
and cleanup, actual-byte quota cleanup, file quota preflight, in-place mutation refusal,
A-stat/B-open/A-restore rejection, ancestor symlink refusal, unsupported syscall refusal,
cache pathname replacement without redirecting writes, operation-name substitution without
foreign deletion, inaccessible-mode requirements, hardlink refusal and output traversal
refusal, destination corruption refusal, mkdir/open/fsync failure, and uncertain failed
open-cleanup reporting, NUL-source refusal and final operation-name identity refusal.
They create only temporary
invented files. Run on Linux:

```sh
python3 -m unittest discover -s tools -p test_snapshot_descriptor_prototype.py -v
```

Windows skips these tests; that result is not acceptance evidence. Linux execution and fresh
review are required. No Docker is needed.

On 2026-10-08 the root reviewer independently executed these exact synthetic modules through
an in-memory SSH-stdin harness on the authorized Linux staging host: 18 tests passed in
0.015 seconds before the two subsequent NUL/final-identity regression tests were added.
No persistent server payload was installed; test temporary directories used
normal scoped cleanup. This is evidence only for the experiment's tested primitives, not a
production helper or full #112 acceptance. Exact-head Ubuntu CI must be checked before merge.

## Intentionally missing

- Host authorization integration and stable root/ancestor acquisition.
- Directory enumeration or atomic cross-file snapshot semantics; inputs are explicit files.
- Hardlinked source parity: source link counts other than one are deliberately refused,
  including legitimate hardlinked libraries. This limitation must not be silently relaxed.
- Completed manifest persistence and non-overwriting atomic publication.
- Process supervisor, cancellation escalation, uncertain-process reconciliation or hard I/O
  deadline. Cooperative checks cannot interrupt a kernel-blocked read/fsync.
- Restart cleanup, real ENOSPC/storage-reserve testing and
  complete error reporting. Cleanup failures propagate; they are not permission to delete
  a guessed or foreign directory.
- Full qBittorrent creation/add/start/stop ownership, peer transfer, retention and resource proof.

Successful copies remain private operation directories owned by the synthetic test; they
are not published or handed to a seeding client. Do not close #112 or claim source-copy or
cutover readiness from this experiment.
