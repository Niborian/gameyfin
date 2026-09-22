# Docker image builder

## Published fork image

GitHub Actions verifies pull requests and publishes the AMD64 image for this
unofficial variant build to `ghcr.io/niborian/gameyfin`. Pull requests verify
the image without publishing it, and merging to `main` does not publish a
package version. After review, a manually started workflow publishes the
matching Gradle/web version tag (for example, `2.4.2`) and updates `latest`.
It does not create SHA-named image tags or registry attestation versions.
Deploy the reviewed image by digest when repeatability matters; `latest`
intentionally tracks the newest reviewed release. The exact commit remains in
the image's OCI revision metadata and signed build provenance stored in GitHub.
Verify provenance for a selected digest with GitHub CLI (authenticate to GHCR
first for a private package):

```bash
gh attestation verify oci://ghcr.io/niborian/gameyfin@sha256:<digest> --repo Niborian/gameyfin
```

This change prevents new `sha256-*` package entries; it does not remove
historical entries already in GHCR.

The workflow deliberately does not publish to the upstream Gameyfin package or
Maven Central. Before changing a running instance, back up its H2 database and
test scanning plus variant behavior against a fixture library that includes
torrent-managed paths, versions, selectable content, grouped archives, and
hardlinks.

Use these scripts when you want to build a local Gameyfin image from this checkout and replace your running container with it.

## Windows PowerShell

```powershell
.\docker\build-image.ps1 -ImageName gameyfin -ImageTag variant-local
```

## Linux/macOS

```bash
./docker/build-image.sh --image gameyfin --tag variant-local
```

The scripts run `clean build`, copy the executable app JAR to `app/build/libs/app.jar`, and build `docker/Dockerfile.ubuntu` with the correct `JAR_FILE` build argument.

To build a production-mode image with signed plugins, provide `GAMEYFIN_KEYSTORE_PASSWORD` and pass `-Production` on PowerShell or `--production` on Bash.

Example compose replacement:

```yaml
services:
  gameyfin:
    image: gameyfin:variant-local
```
