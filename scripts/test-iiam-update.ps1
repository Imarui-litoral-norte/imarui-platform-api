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

Add-Type -AssemblyName System.IO.Compression.FileSystem
function New-TestJar([string]$path,[string]$sql) {
    $z=[IO.Compression.ZipFile]::Open($path,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach($entry in @(@('BOOT-INF/classes/br/com/imarui/ima/IdentityServiceApplication.class','class'),@('BOOT-INF/classes/db/migration/V1.sql',$sql))) {
            $w=New-Object IO.StreamWriter($z.CreateEntry($entry[0]).Open());try{$w.Write($entry[1])}finally{$w.Dispose()}
        }
    }finally{$z.Dispose()}
}
$a=Join-Path $testRoot 'crlf.jar';$b=Join-Path $testRoot 'lf.jar';$c=Join-Path $testRoot 'changed.jar'
New-TestJar $a "select 1;`r`n";New-TestJar $b "select 1;`n";New-TestJar $c "select 2;`n"
$x=Assert-IiamJar $a;$y=Assert-IiamJar $b;$z=Assert-IiamJar $c
$key='BOOT-INF/classes/db/migration/V1.sql'
if($x[$key] -ne $y[$key]){throw 'Line ending normalization failed'}
if($x[$key] -eq $z[$key]){throw 'Changed migration not detected'}
Write-Output 'PASS: migration line endings ignored; actual SQL changes detected.'
