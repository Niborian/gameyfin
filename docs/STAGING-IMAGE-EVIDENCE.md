# Isolated staging image evidence

PR image builds retain a short-lived Docker archive, SHA-256 checksum, source head,
and image ID as a GitHub Actions artifact. This exports the existing tested build;
it does not push a registry tag, publish a release, or move `latest`.

Before testing, verify all checks for the recorded head and review its diff. Download
the artifact from that exact run, verify the archive checksum, load it only on the
authorized staging server, and verify the loaded image revision label and image ID.
The PR head label is not a replacement for reviewing the build's source and CI run.

Use synthetic fixtures, new private data directories, separate application keys,
bounded CPU/memory, and no production mounts. Do not start or use local Docker.
Run sequentially against the same fixture as a clone of the running baseline image.
Prefer no network and no published ports; only enable explicitly necessary isolated
test connectivity. Disable torrent networking in staging. Record startup, health,
counts, exact/grouped download hashes, scan outcomes/duration, peak resource use,
restart count, and fixture hashes before and after. Test H2 backups only while the
isolated instance is stopped; never open the live production database.

Record every created container, image, directory, and network. Remove only those
exact resources after collecting evidence; never prune shared Docker resources.

This archive supports comparison, not cutover. Issue #61 remains open until the
full baseline, meaningful improvement/no regression, backup/restore, exposure,
rollback, published digest/tag parity, provenance/SBOM and UI-version criteria are
evidenced. A passing PR image build alone proves none of those runtime criteria.
