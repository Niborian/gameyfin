#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="gameyfin"
IMAGE_TAG="variant-local"
PLATFORM=""
PRODUCTION=false
NO_BUILD=false
NO_CACHE=false

usage() {
  cat <<'EOF'
Usage: docker/build-image.sh [options]

Options:
  --image NAME       Docker image name (default: gameyfin)
  --tag TAG          Docker image tag (default: variant-local)
  --platform VALUE   Buildx platform, for example linux/amd64
  --production       Run Gradle with -Pvaadin.productionMode=true
  --no-build         Reuse existing app/plugin build outputs
  --no-cache         Disable Docker layer cache
  -h, --help         Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --image)
      IMAGE_NAME="$2"
      shift 2
      ;;
    --tag)
      IMAGE_TAG="$2"
      shift 2
      ;;
    --platform)
      PLATFORM="$2"
      shift 2
      ;;
    --production)
      PRODUCTION=true
      shift
      ;;
    --no-build)
      NO_BUILD=true
      shift
      ;;
    --no-cache)
      NO_CACHE=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [[ "$NO_BUILD" == false ]]; then
  gradle_args=(clean build)
  if [[ "$PRODUCTION" == true ]]; then
    gradle_args+=(-Pvaadin.productionMode=true)
  fi
  ./gradlew "${gradle_args[@]}"
fi

app_jar="$(find app/build/libs -maxdepth 1 -type f -name 'app-*.jar' ! -name '*-plain.jar' | sort | tail -n 1)"
if [[ -z "$app_jar" ]]; then
  echo "No executable app JAR found in app/build/libs. Run without --no-build or build the app first." >&2
  exit 1
fi

cp "$app_jar" app/build/libs/app.jar

image_ref="${IMAGE_NAME}:${IMAGE_TAG}"
if [[ -n "$PLATFORM" ]]; then
  docker_args=(buildx build --load --platform "$PLATFORM")
else
  docker_args=(build)
fi

docker_args+=(
  -f docker/Dockerfile.ubuntu
  --build-arg JAR_FILE=./app/build/libs/app.jar
  -t "$image_ref"
)

if [[ "$NO_CACHE" == true ]]; then
  docker_args+=(--no-cache)
fi
docker_args+=(.)

docker "${docker_args[@]}"

echo "Built Docker image $image_ref"
