# Docker image builder

## Published fork image

GitHub Actions verifies pull requests and publishes the AMD64 image for this
unofficial variant build to `ghcr.io/niborian/gameyfin`. Every publish receives
an immutable `sha-<commit>` tag; a human-triggered workflow may add a reviewed
release tag. Deploy the SHA tag or its digest, never a mutable tag.

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
