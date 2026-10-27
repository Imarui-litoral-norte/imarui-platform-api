param(
    [Parameter(Mandatory=$true)][ValidateSet('dev','prod')][string]$Environment,
    [string]$WorkspaceRoot=(Join-Path $PSScriptRoot '../..')
)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath($WorkspaceRoot)
if(!(Test-Path -LiteralPath (Join-Path $workspace 'logimarui-core-api'))){throw 'Workspace deve conter logimarui-core-api'}
$templates=Join-Path $PSScriptRoot 'workspace'
$backup=Join-Path $workspace ('.runtime/launcher-install-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$manifest=@()
foreach($name in @('executar-core.bat','refresh.bat','Finish.bat','logimarui-core-api-lifecycle.ps1')){
    $group=if($name -eq 'logimarui-core-api-lifecycle.ps1'){$Environment}else{'common'}
    $source=Join-Path (Join-Path $templates $group) $name
    $target=Join-Path $workspace $name
    if(!(Test-Path -LiteralPath $source)){throw ('Template ausente: '+$name)}
    if(Test-Path -LiteralPath $target){
        $copy=Join-Path $backup $name
        Copy-Item -LiteralPath $target -Destination $copy
        $manifest+=[pscustomobject]@{file=$name;sha256=(Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash}
    }
    Copy-Item -LiteralPath $source -Destination $target
}
$manifest|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $backup 'manifest.json') -Encoding UTF8
Write-Output ('Launchers '+$Environment+' instalados em '+$workspace+'. Backup: '+$backup)
