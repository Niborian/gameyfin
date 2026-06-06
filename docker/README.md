# Docker image builder

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
