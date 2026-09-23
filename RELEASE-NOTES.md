# Unofficial Gameyfin 2.4.3 candidate

This is the Niborian fork, **not an original Gameyfin release**. The version in the UI, Gradle build, and candidate package tag is `2.4.3`. Preparing this candidate does not publish an image or move `latest`.

Changes since the last published `2.4.2` build:

- Exact optional-content download selection and broader combination tests ([#42](https://github.com/Niborian/gameyfin/pull/42), [#59](https://github.com/Niborian/gameyfin/pull/59)); grouped archive members now show their filenames ([#60](https://github.com/Niborian/gameyfin/pull/60)). Explicit empty selections and unknown content IDs are handled predictably ([#72](https://github.com/Niborian/gameyfin/pull/72)).
- Release-name suggestions, including a neutral multiplayer-fix label, with review rather than destructive automatic regrouping ([#56](https://github.com/Niborian/gameyfin/pull/56)).
- Hardlink/source-integrity and repeated-rescan fixture coverage ([#46](https://github.com/Niborian/gameyfin/pull/46), [#62](https://github.com/Niborian/gameyfin/pull/62)).
- CSRF and login-redirect hardening ([#50](https://github.com/Niborian/gameyfin/pull/50), [#51](https://github.com/Niborian/gameyfin/pull/51)).
- Scan health metrics and a concurrent company-metadata collision fix ([#52](https://github.com/Niborian/gameyfin/pull/52), [#64](https://github.com/Niborian/gameyfin/pull/64)).
- GitHub-hosted provenance without registry attestation tags ([#58](https://github.com/Niborian/gameyfin/pull/58)); the separate candidate/promotion gate is tracked in [#67](https://github.com/Niborian/gameyfin/issues/67).

Still required before claiming this is better than the running instance or moving `latest`: a consistent H2 backup restored in isolation ([#26](https://github.com/Niborian/gameyfin/issues/26)), a representative memory/scan comparison ([#27](https://github.com/Niborian/gameyfin/issues/27)), direct-port and proxy/authentication verification ([#29](https://github.com/Niborian/gameyfin/issues/29)), and the full side-by-side cutover evidence in [#61](https://github.com/Niborian/gameyfin/issues/61). No TrueNAS deployment is part of this candidate.
