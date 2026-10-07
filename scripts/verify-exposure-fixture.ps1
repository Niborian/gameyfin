param([switch]$ConfigurationOnly)
$ErrorActionPreference = 'Stop'
$taskCompose = Join-Path $PSScriptRoot '../docker/exposure-fixture/compose.yml'
$taskConfigText = & docker compose -f $taskCompose config --format json
if ($LASTEXITCODE -ne 0) { throw 'Unable to render staging Compose configuration' }
$taskConfig = ($taskConfigText -join "`n") | ConvertFrom-Json
if ($taskConfig.services.gameyfin.ports) { throw 'Gameyfin backend must not publish host ports' }
$taskPublished = @($taskConfig.services.proxy.ports)
if ($taskPublished.Count -ne 1 -or $taskPublished[0].host_ip -ne '127.0.0.1' -or $taskPublished[0].published -ne '39080') {
    throw 'Proxy must publish only the intended loopback fixture port'
}
if ($taskConfig.services.gameyfin.volumes | Where-Object { $_.type -ne 'volume' }) {
    throw 'Fixture backend must use isolated named volumes only'
}
if ($ConfigurationOnly) { Write-Output 'PASS: isolated volumes, no backend host ports, loopback-only proxy'; return }
$taskBackend = & docker compose -f $taskCompose ps -q gameyfin
if ($LASTEXITCODE -ne 0 -or -not $taskBackend) { throw 'Start the isolated fixture first' }
$taskBindings = & docker inspect --format '{{json .HostConfig.PortBindings}}' $taskBackend
if ($LASTEXITCODE -ne 0 -or ($taskBindings -ne 'null' -and $taskBindings -ne '{}')) { throw 'Unexpected backend host port binding' }
function Get-FixtureStatus([string]$Path) {
    $taskStatus = & curl.exe --silent --show-error --max-time 15 --output NUL --write-out '%{http_code}' "http://127.0.0.1:39080$Path"
    if ($LASTEXITCODE -ne 0) { throw "Fixture request failed: $Path" }
    return $taskStatus
}
if ((Get-FixtureStatus '/login') -ne '200') { throw 'Expected Gameyfin login through proxy' }
if ((Get-FixtureStatus '/') -ne '302') { throw 'Expected login redirect for anonymous access after fixture setup' }
if ((Get-FixtureStatus '/actuator/health') -ne '404') { throw 'Management endpoint reached through proxy' }
Write-Output 'PASS: login reachable, anonymous root redirects, management blocked, backend unpublished'
