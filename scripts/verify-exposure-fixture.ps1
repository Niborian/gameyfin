param([switch]$ConfigurationOnly)
$ErrorActionPreference = 'Stop'
$taskCompose = Join-Path $PSScriptRoot '../docker/exposure-fixture/compose.yml'
$taskConfigText = & docker compose -f $taskCompose config --format json
if ($LASTEXITCODE -ne 0) { throw 'Unable to render staging Compose configuration' }
$taskConfig = ($taskConfigText -join "`n") | ConvertFrom-Json
if ($taskConfig.services.gameyfin.ports) { throw 'Gameyfin backend must not publish host ports' }
$taskPublished = @($taskConfig.services.proxy.ports)
if ($taskPublished.Count -ne 1 -or $taskPublished[0].host_ip -ne '127.0.0.1' -or
    [int]$taskPublished[0].published -lt 1 -or [int]$taskPublished[0].published -gt 65535) {
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
$taskProxy = & docker compose -f $taskCompose ps -q proxy
if ($LASTEXITCODE -ne 0 -or -not $taskProxy) { throw 'Start the isolated proxy first' }
$taskProxyBindingsText = & docker inspect --format '{{json .HostConfig.PortBindings}}' $taskProxy
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect proxy host port bindings' }
$taskProxyBindings = $taskProxyBindingsText | ConvertFrom-Json
$taskProxyPortNames = @($taskProxyBindings.PSObject.Properties.Name)
$taskRuntimePorts = @($taskProxyBindings.'8080/tcp')
if ($taskProxyPortNames.Count -ne 1 -or $taskProxyPortNames[0] -ne '8080/tcp' -or
    $taskRuntimePorts.Count -ne 1 -or $taskRuntimePorts[0].HostIp -ne '127.0.0.1' -or
    [string]$taskRuntimePorts[0].HostPort -ne [string]$taskPublished[0].published) {
    throw 'Running proxy must publish exactly the configured loopback fixture port'
}
function Get-FixtureStatus([string]$Path) {
    $taskCurl = (Get-Command curl -CommandType Application -ErrorAction Stop).Source
    $taskNull = if ($IsWindows) { 'NUL' } else { '/dev/null' }
    $taskPort = [int]$taskPublished[0].published
    $taskStatus = & $taskCurl --silent --show-error --max-time 15 --output $taskNull --write-out '%{http_code}' "http://127.0.0.1:$taskPort$Path"
    if ($LASTEXITCODE -ne 0) { throw "Fixture request failed: $Path" }
    return $taskStatus
}
if ((Get-FixtureStatus '/login') -ne '200') { throw 'Expected Gameyfin login through proxy' }
if ((Get-FixtureStatus '/') -ne '302') { throw 'Expected login redirect for anonymous access after fixture setup' }
foreach ($taskManagementPath in @('/actuator/health', '/actuator/metrics', '/actuator/env')) {
    if ((Get-FixtureStatus $taskManagementPath) -ne '404') { throw "Management endpoint reached through proxy: $taskManagementPath" }
}
Write-Output 'PASS: login reachable, anonymous root redirects, management blocked, backend unpublished'
