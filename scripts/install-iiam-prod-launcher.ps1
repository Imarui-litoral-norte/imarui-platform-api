$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$private=Join-Path $workspace '.runtime/iiam'
$launcher=Join-Path $workspace 'logimarui-core-api-lifecycle.ps1'
if(!(Test-Path -LiteralPath (Join-Path $private 'iiam.properties'))){throw 'Prepare production IIAM configuration first'}
$source=[IO.File]::ReadAllText($launcher)
if($source.Contains('# IIAM_PROD_DISPATCH')){Write-Output 'Production lifecycle dispatch already installed';return}
$backup=Join-Path $private 'lifecycle-before-iiam.ps1'
if(Test-Path -LiteralPath $backup){throw 'Launcher backup already exists; investigate before changing it'}
Copy-Item -LiteralPath $launcher -Destination $backup
$anchor="$"+'ErrorActionPreference = ''Stop'''
if(!$source.Contains($anchor)){throw 'Unknown lifecycle launcher format'}
$dispatch=@'
# IIAM_PROD_DISPATCH
$iiamLauncher=Join-Path $PSScriptRoot 'logimarui-core-api/scripts/start-iiam-integrated-prod.ps1'
if(Test-Path -LiteralPath (Join-Path $PSScriptRoot '.runtime/iiam/iiam.properties')){
    if($Action -eq 'profile'){
        [pscustomobject]@{logimaruiProfile='local';springProfile='local';coreApiPort=443;frontendPort=8091}
    }else{& $iiamLauncher -Action $Action}
    return
}
'@
[IO.File]::WriteAllText($launcher,$source.Replace($anchor,$anchor+"`r`n"+$dispatch))
Write-Output 'Existing start/stop/restart entry points now use the integrated production launcher.'
