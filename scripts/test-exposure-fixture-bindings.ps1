$ErrorActionPreference = 'Stop'
# No Docker daemon or HTTP requests are used: every case must fail before curl.
function docker {
    $global:LASTEXITCODE = 0
    if ($args -contains 'config') {
        return '{"services":{"gameyfin":{"volumes":[{"type":"volume"}]},"proxy":{"ports":[{"host_ip":"127.0.0.1","published":"39080"}]}}}'
    }
    if ($args -contains 'ps') { return [string]$args[-1] }
    if ($args[-1] -eq 'gameyfin') { return '{}' }
    return $taskBindingJSON
}
$taskCases = @(
    '{"8080/tcp":[{"HostIp":"0.0.0.0","HostPort":"39080"}]}',
    '{"8080/tcp":[{"HostIp":"127.0.0.1","HostPort":"39081"}]}',
    '{"8080/tcp":[{"HostIp":"127.0.0.1","HostPort":"39080"}],"8081/tcp":[{"HostIp":"127.0.0.1","HostPort":"39081"}]}',
    '{}'
)
foreach ($taskBindingJSON in $taskCases) {
    try {
        & (Join-Path $PSScriptRoot 'verify-exposure-fixture.ps1')
        throw 'Expected runtime binding rejection'
    } catch {
        if ($_.Exception.Message -ne 'Running proxy must publish exactly the configured loopback fixture port') { throw }
        Write-Output 'PASS: rejected unsafe runtime binding'
    }
}
