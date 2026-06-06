param(
    [string]$ImageName = "gameyfin",
    [string]$ImageTag = "variant-local",
    [string]$Platform = "",
    [switch]$Production,
    [switch]$NoBuild,
    [switch]$NoCache
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot
try {
    if (-not $NoBuild) {
        $gradleArgs = @("clean", "build")
        if ($Production) {
            $gradleArgs += "-Pvaadin.productionMode=true"
        }

        & ".\gradlew.bat" @gradleArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE"
        }
    }

    $appJar = Get-ChildItem -Path "app\build\libs" -Filter "app-*.jar" |
        Where-Object { $_.Name -notlike "*-plain.jar" } |
        Sort-Object Length -Descending |
        Select-Object -First 1

    if (-not $appJar) {
        throw "No executable app JAR found in app\build\libs. Run without -NoBuild or build the app first."
    }

    Copy-Item -LiteralPath $appJar.FullName -Destination "app\build\libs\app.jar" -Force

    $imageRef = "${ImageName}:$ImageTag"
    if ($Platform) {
        $dockerArgs = @(
            "buildx", "build",
            "--load",
            "--platform", $Platform,
            "-f", "docker/Dockerfile.ubuntu",
            "--build-arg", "JAR_FILE=./app/build/libs/app.jar",
            "-t", $imageRef
        )
    } else {
        $dockerArgs = @(
            "build",
            "-f", "docker/Dockerfile.ubuntu",
            "--build-arg", "JAR_FILE=./app/build/libs/app.jar",
            "-t", $imageRef
        )
    }

    if ($NoCache) {
        $dockerArgs += "--no-cache"
    }
    $dockerArgs += "."

    & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Docker build failed with exit code $LASTEXITCODE"
    }

    Write-Host "Built Docker image $imageRef"
}
finally {
    Pop-Location
}
