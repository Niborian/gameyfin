# Docker image builder

## Published fork image

GitHub Actions verifies pull requests and publishes the AMD64 image for this
unofficial variant build to `ghcr.io/niborian/gameyfin`. A merge to `main`
creates only an immutable `sha-<commit>` candidate. After review, a manually
started workflow adds the matching Gradle/web version tag (for example,
`2.4.1`) and updates `latest` to that release build. Deploy that reviewed
version tag or its digest when repeatability matters; `latest` intentionally
tracks the newest reviewed release.

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

## Readiness and scan failures

The container health check polls the management-port readiness endpoint from
inside the container. It allows three minutes for a cold start and reports
`unhealthy` if the application or its plugins never become ready. Port 8081
does not need to be published to the host for this check.

After starting a reviewed image, check `docker inspect --format '{{.State.Health.Status}}' gameyfin`
and `docker logs --since 30m gameyfin`. A healthy container is not proof that
the last library scan succeeded: monitor `gameyfin_scans_failed_total` and
`gameyfin_scans_active` on the private Prometheus endpoint as separate
signals. `gameyfin_scans_failures_by_kind_total` separates database failures
from other errors with fixed `type` and `kind` labels, without putting paths
or exception messages in metrics. An increase in failed scans, or an active
scan that never returns to zero, warrants inspection of the corresponding
scan progress and logs.

The default JVM options exit on Java heap exhaustion so the restart policy can
recover the process. An unexpected container restart or an `OutOfMemoryError`
in logs is an incident, not a successful scan. Preserve the logs and H2 backup,
check available memory and database errors, and do not retry a large scan or
change the live image until the backup has been restored in isolation. Keep
management and Prometheus endpoints private; do not publish port 8081.
