<div align="center">
    <a href="https://gameyfin.org">
        <img src="assets/v2/Banner.svg" width="auto" alt="Gameyfin Logo">
    </a>

</div>
<div align="center">
    <h2>Gameyfin</h2>
    <h4>Manage your video games.</h4>
    <p>simple / fast / <a href="https://gameyfin.org/blog/2025/12/22/why-gameyfin-is-foss/">FOSS</a></p>
</div>

## Overview

Name and functionality inspired by [Jellyfin](https://jellyfin.org/).

> [!IMPORTANT]
> **This is not an official Gameyfin image or upstream release.** It is an independent Niborian fork, is not endorsed by
> the Gameyfin maintainers. You may deploy this fork as `ghcr.io/niborian/gameyfin`, but it is provided without any
> guarantee that it works for your library or environment. If a fork image has a problem, please report it here—not to
> the original Gameyfin maintainers. Reviewed fork images identify their revision in container metadata.

This fork exists to prototype variant/version support, selectable extra content, grouped archive downloads,
hardlink-friendly library handling, and metadata tools for keeping torrent-managed paths in place.

Gameyfin will turn your disorganized collection of video games into a beautiful, easy-to-navigate library that you can
access from any device with a web browser.  
It will automatically scan your game folders, download metadata and cover images, and present everything in a
user-friendly interface.  
Download your game files directly from the web UI, share your library with friends, and enjoy your games like never
before.

### Original Gameyfin documentation

The original project's [documentation and screenshots](https://gameyfin.org/) and
[GitHub repository](https://github.com/gameyfin/gameyfin) are the right starting point for understanding Gameyfin.
Their installation guide targets the original image, however, and does not validate this fork's variants, paths, or
release process.

### Added by this fork

This fork adds experimental support for libraries where one visible game entry can contain multiple versions and
variants without moving the original source files.

Highlights:

* Version-aware variants, with the latest `Normal` version selected by default unless an admin pins another default.
* User-selectable DLC, patches, mods, extras, and dedicated server content per download.
* Shared optional content that can apply to multiple versions.
* Grouped content paths so multipart archives can appear as one selectable download item.
* Admin tools for attaching existing paths, grouping duplicates, and ignoring attached source paths so scans do not
  recreate duplicate games.
* Hardlink mirror storage mode for libraries that need managed access without breaking torrent paths.

The images below summarize the added behavior.

<p align="center">
    <img src="assets/variant-support/variant-content-model.svg" width="820" alt="Diagram showing variants and shared optional content">
</p>

## Planned work

The fork is organized around user outcomes, not undifferentiated feature work. Every pull request must close a
milestone-backed issue, describe the visible behavior it adds, and pass the required quality checks.

| Milestone | What it delivers |
| --- | --- |
| [Release foundation](https://github.com/Niborian/gameyfin/milestone/1) | One visible release version, a matching reviewed package tag, provenance, SBOM, and a repeatable release gate. |
| [Variant library integrity](https://github.com/Niborian/gameyfin/milestone/2) | Rescans preserve variants, selected defaults, hardlinks, and original source paths. |
| [Selectable downloads](https://github.com/Niborian/gameyfin/milestone/3) | Users receive exactly the version and optional content they choose, including grouped/shared archives. |
| [Production operations](https://github.com/Niborian/gameyfin/milestone/4) | Recoverable H2 backups, memory-safe scans, health checks, and restricted access exposure. |
| [Upstream compatibility](https://github.com/Niborian/gameyfin/milestone/5) | Future upstream changes are merged and tested without losing fork behavior. |
| [Library intelligence and request automation](https://github.com/Niborian/gameyfin/milestone/6) | Explainable release grouping, lawful update discovery, and reviewed request automation. |

See the [issue backlog](https://github.com/Niborian/gameyfin/issues) for concrete acceptance criteria.

### Non-negotiable library guarantees

- Scanning, grouping, and retirement must not move, rename, delete, or rewrite torrent-managed source files.
- Low-confidence release matches remain in an administrator review queue; a suggestion never silently replaces a
  selected version.
- Older versions are superseded and archived before any cleanup. Only application-managed mirrors or caches may be
  pruned after an explicit review and grace period.
- Download automation is limited to administrator-approved, authorized sources. It does not bypass store licensing,
  DRM, or access controls.

### Version and image identity

`build.gradle.kts` is the canonical release version. The build synchronizes it into the frontend, where it is displayed
in the Gameyfin footer. A reviewed release publishes the same version as a package tag, for example
`ghcr.io/niborian/gameyfin:2.4.0-variants.1`; every candidate also receives an immutable `sha-<commit>` tag.

Pull requests run tests but cannot publish an image. A merge to `main` produces an immutable SHA candidate, and a
manual reviewed release run adds the matching semantic version tag. Deploy a reviewed version tag or pinned digest,
never an untraceable local tag or a mutable `latest` tag.

<p align="center">
    <img src="assets/variant-support/download-selection.svg" width="820" alt="Diagram showing selectable download content">
</p>

## Original Gameyfin capabilities

The following baseline capabilities come from [original Gameyfin](https://github.com/gameyfin/gameyfin), not from this
fork's variant work:

✨ Automatically scans and indexes your game libraries  
⬇️ Access your library via your web browser & download games directly from there  
👥 Share your library with friends & family  
⚛️ LAN-friendly (everything is cached locally - except for videos)  
🐋 Runs in a container or any system with a JVM  
🌈 Themes (including colorblind support)  
🔌 Easily expandable with plugins  
🔒 Integrates into your SSO solution via OAuth2 / OpenID Connect  
🆓 **100% open source and free to use without any paywall.**

### Contribute to Gameyfin

This is a personal Niborian fork, maintained primarily for its own game library and workflow. It is not intended to
compete with, represent, or replace the original Gameyfin project.

The original Gameyfin maintainers are welcome to adopt any useful changes from this repository under its AGPL-3.0
license. If a contributor wants their change considered for upstream Gameyfin, they should coordinate with the original
maintainers first. Keep pull requests here focused, linked to a milestone-backed issue, and respectful of the upstream
project.

### Technical Details

Gameyfin v2 is written in Kotlin and uses the following libraries/frameworks:

* Spring Boot 3 for the backend
* Vaadin Hilla & React for the frontend
* PF4J for the plugin system
* H2 database for persistence

### Acknowledgements

[![YourKit Logo](https://www.yourkit.com/images/yklogo.png)](https://www.yourkit.com/)  
Gameyfin is supported by [YourKit](https://www.yourkit.com/), the makers
of [YourKit Java Profiler](https://yourkit.com/java/profiler/), a powerful tool for profiling Java and Kotlin
applications.
