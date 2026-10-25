# First-cutover evidence status

Issue #61 is not complete. This document distinguishes current observations from
unproven acceptance; no production deployment or tag promotion is authorized here.

## Read-only baseline refreshed 2026-10-07

- Running local image ID: `sha256:b031128b79ce56a7a7ea59498d1894ae5a28b0309952bd917ee7c3f9936d3729`.
  No registry digest is recorded for this image. Container image labels report `26.04`,
  while the authenticated application UI reports `2.4.0`; do not assume these labels
  identify a clean upstream release or the current fork source revision.
- Four administrator-visible libraries contain 104, 27, 1, and 0 games, respectively.
  The unfiltered search renders 132 distinct game entries. Variant/content and
  ignored-path counts were not established in this October 7 UI observation; a later
  cold capture established historical counts below, not current live counts.
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

## Completed backup gate and later evidence

The approved October 9 consistent cold capture and same-image isolated restore
completed [#26](https://github.com/Niborian/gameyfin/issues/26). The captured database
contained four libraries, 132 games, 137 variants, 144 contents and 15 ignored-path
entries. Pre/postboot restore counts matched; the isolated application reported
health UP and login HTTP 200. Production configuration was unchanged, disposable
restore resources were removed, and the exact rollback archive was retained.
These are counts of that dated capture, not a fresh count of the running database.
Successful login-page delivery is not authenticated browser acceptance. Future
cutover still needs a suitable current backup and candidate-specific rollback proof.

Later bounded experiments are recorded in [#27](https://github.com/Niborian/gameyfin/issues/27).
They include isolated synthetic cold/repeat/interrupted-recovery scans, exact selected
downloads and opt-in JVM reclamation profiles. Completed synthetic cases preserved
source hashes and one default variant per fork fixture game, while the baseline
fixture retained two. These results do not establish a representative real-library
memory budget or validate the records affected by the original interrupted scan.

The preliminary October 10 browsing/recovery comparison observed lower tester
container memory than live, but caches, versions and JVM budgets differed. It is not
a matched language/runtime performance result. Heavier sequential scan/metadata
measurements and post-task CPU/memory recovery remain to be validated; do not infer
cutover readiness or a shipping-default tuning recommendation from these observations.

## Historical isolated paired comparison

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

The 2026-10-25 delivery scope replaces the former benchmark campaign with the normal
production build and focused regression checks. Historical measurements above remain
historical evidence, not measurements of the new candidate. Do not restart the campaign.

- Exact candidate review and focused checks for variant editing/detection, migrations,
  exact/grouped downloads, source preservation and plugin configuration security.
  Linux-specific filesystem cases must execute on Linux; Windows skips are not passes.
- Refresh current production baseline counts and source integrity evidence. Historical
  cold-capture counts above are not current live-database verification.
- The October 9 backup/restore gate is complete under #26; confirm backup suitability
  for the eventual cutover and prove candidate-specific rollback before switching.
- Reviewed production exposure/torrent behavior, including browser WebSocket/reconnect,
  relevant firewall/router inventory and chosen direct-admin/client allowlists. The
  October 7 inventory found wildcard direct ports and no inspected source allowlist;
  router/Internet forwarding remains unknown. No production binding/firewall change
  is approved. Preserve the existing hostname/authentication and independent qBittorrent
  policy; staging alone cannot prove live restrictions.
- Once publication is separately approved: reviewed immutable image digest, UI/image
  version parity, satisfactory provenance/SBOM and CodeQL. Any latest promotion is a
  separate deliberate action; existing registry tags do not prove the new candidate.

Keep the existing `ix-gameyfin / gameyfin` service identity. Preparing a candidate
does not authorize deployment, production changes or a parallel tester.

No recommendation to update production is made until these gates are evidenced.
