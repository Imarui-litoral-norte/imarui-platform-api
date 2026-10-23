$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'managed-runtime-ownership.ps1')
$registered=[DateTimeOffset]::Parse('2026-10-23T10:00:03Z')
$record=[pscustomobject]@{startedAt=$registered.ToUnixTimeSeconds()}
Assert-ManagedRuntimeProcessTime $record ([pscustomobject]@{CreationDate=[datetime]::Parse('2026-10-23T10:00:00Z')})
Assert-ManagedRuntimeProcessTime $record $null
$refused=0
foreach($pair in @(
    @($record,([pscustomobject]@{CreationDate=[datetime]::Parse('2026-10-23T10:00:04Z')})),
    @(([pscustomobject]@{}),([pscustomobject]@{CreationDate=[datetime]::Parse('2026-10-23T10:00:00Z')})),
    @($record,([pscustomobject]@{}))
)){
    try{Assert-ManagedRuntimeProcessTime $pair[0] $pair[1]}
    catch{$refused++}
}
if($refused -ne 3){throw 'A reused or unknown process timestamp was accepted'}
Write-Output 'Runtime ownership: 5 metadata checks passed; no processes stopped.'
