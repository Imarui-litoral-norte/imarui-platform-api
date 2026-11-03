$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'update-iiam-prod.ps1') -LibraryOnly
$testRoot=Join-Path $env:TEMP ('iiam-swap-test-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $testRoot | Out-Null
$artifact=Join-Path $testRoot 'active.jar';$candidate=Join-Path $testRoot 'new.jar';$backup=Join-Path $testRoot 'old.jar'
function Stop-Iiam {$script:stops++}
function Start-Iiam {$script:starts++}
function Wait-Iiam {if($script:failNew -and $script:starts -eq 1){throw 'simulated startup failure'}}
foreach($fail in @($false,$true)) {
    [IO.File]::WriteAllText($artifact,'old');[IO.File]::WriteAllText($candidate,'new');[IO.File]::WriteAllText($backup,'old')
    $script:starts=0;$script:stops=0;$script:failNew=$fail;$caught=$false
    try {Invoke-IiamSwap}catch{$caught=$true;if(!$fail){throw}}
    if($fail) {
        if(!$caught -or [IO.File]::ReadAllText($artifact) -ne 'old' -or !(Test-Path $backup) -or $script:starts -ne 2){throw 'Rollback failed'}
    } else {
        if($caught -or [IO.File]::ReadAllText($artifact) -ne 'new' -or (Test-Path $backup)){throw 'Successful swap failed'}
    }
}
# A stop failure must not touch either artifact.
function Stop-Iiam {throw 'simulated ownership mismatch'}
[IO.File]::WriteAllText($artifact,'old');$caught=$false
try {Invoke-IiamSwap}catch{$caught=$true}
if(!$caught -or [IO.File]::ReadAllText($artifact) -ne 'old'){throw 'Stop failure modified artifact'}
Write-Output 'PASS: successful activation, failed startup rollback, and refused stop preserve expected artifacts.'
