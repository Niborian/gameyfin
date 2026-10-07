# First-cutover evidence status

Issue #61 is not complete. This document distinguishes current observations from
unproven acceptance; no production deployment or tag promotion is authorized here.

## Read-only baseline refreshed 2026-10-07

- Running local image ID: `sha256:b031128b79ce56a7a7ea59498d1894ae5a28b0309952bd917ee7c3f9936d3729`.
  No registry digest is recorded for this image. Container image labels report `26.04`,
  while the authenticated application UI reports `2.4.0`; do not assume these labels
  identify a clean upstream release or the current fork source revision.
- Four administrator-visible libraries contain 104, 27, 1, and 0 games, respectively.
  The unfiltered search renders 132 distinct game entries. Total variant/content and
  ignored-path counts have not yet been established.
- Scan concurrency is four. File watchers, empty-directory scanning, and daily metadata
  refresh are enabled. These settings were inspected, not changed.
- A representative game deep link renders correctly after loading, with one Normal
  variant, a required 11.4 GiB base archive, and an unchecked optional 9.9 MiB patch.
  No large production download was triggered. Previous empty-page observations did
  not wait for loading and are not confirmed defects.
- Direct application login works. The existing reverse proxy uses a separate
  authentication middleware. The direct UI and tracker/peer ports listen on IPv4/IPv6
  wildcard interfaces; this does not establish Internet reachability.
- The container has zero restarts, no configured memory limit, and no Docker healthcheck.
  A read-only sample reported approximately 787.9 MiB Docker memory usage. Application
  health was UP; scan counters/durations were zero since the current process started,
  and inspected recent logs had no ERROR/OOM/H2-closed matches. This proves idle health,
  not successful controlled full scans or a safe scan budget.

## Isolated paired comparison

The authorized baseline clone uses the exact local image ID, fresh synthetic data,
separate credentials/key, no network or published ports, two CPUs, and a 1536 MiB memory
limit. Startup, anonymous redirect, staging-only login, authenticated empty catalog,
and private health probes pass. An idle sample is not representative scan evidence.

CI-exported candidate archives are test inputs only. Record exact CI head, archive
checksum, loaded image ID, fixture inventories/hashes, measurements, failure/recovery,
backup/restore duration, and scoped cleanup before drawing a comparison conclusion.

The reviewed CI candidate at `0f152220a5c73b938312f43e464291fd1dfb9511`
was compared sequentially against the exact baseline image with identical pristine
synthetic H2 data and files. This candidate includes the variant-integrity change,
not the later bounded-scheduler or retirement changes. It is not a published release.

- A full scan on the baseline leaves both pinned 1.0 and discovered 1.1 as defaults;
  the candidate retains only the pinned default and marks 1.1 latest. The expanded
  four-library, 132-game fixture reproduced 264 defaults on baseline versus 132 on
  candidate, with all libraries completing and no reported scan failures.
- Required grouped downloads and optional patch selection produced identical exact
  ZIP member hashes and 14/28-byte estimates on both images. All 924 synthetic source
  file hashes remained unchanged across quick, full, and repeated full scans.
- First full scan: baseline 7.940 seconds / 544,067,584 bytes sampled RSS; candidate
  7.968 seconds / 557,756,416 bytes. Repeated full scan: baseline 3.270 seconds /
  547,115,008 bytes; candidate 3.369 seconds / 562,941,952 bytes. Times include API
  completion polling, and sampled RSS is not an absolute peak. The approximately
  2.5-2.9% RSS increase is disclosed, not a memory-efficiency improvement. Additional
  repetitions and a stated regression threshold are needed before release acceptance.
- Candidate startup upgraded the isolated database from 24 to 33 migrations.
  Restoration requires retaining the staging application key and restoring directory
  ownership appropriate to the image's runtime user. Initial copy ownership/key
  mistakes were corrected only in isolated staging; production data was not copied.
- An offline synthetic H2 SCRIPT/RUNSCRIPT rehearsal restored four libraries, 132
  variants and 264 content rows into a new database. The baseline image booted that
  restored database, authenticated, reported health UP, and reproduced exact downloads.
  This is not evidence of a consistent production backup.
- Separate disposable proxy fixtures passed anonymous login/root/management checks
  (200/302/404), setup, authenticated browse/session and logout for both images.
  Backends were unpublished and proxies loopback-only. Their four containers, eight
  volumes and two networks were removed; production resources were unchanged.

## Remaining release gates

- Same-fixture candidate comparison and representative workload, including exact/grouped
  downloads, scan reliability, resource use, and unchanged source hashes/hardlinks.
- Complete production baseline counts and source integrity evidence.
- Consistent production backup and isolated restoration, plus rollback proof.
- Reviewed production exposure/torrent behavior; staging alone cannot prove live bindings.
- Published version and latest resolving to one reviewed digest, UI/image version parity,
  satisfactory provenance/SBOM and CodeQL. The last inspected published `2.4.3` and
  `latest` digests differ and do not establish readiness for the current PR changes.

No recommendation to update production is made until these gates are evidenced.
