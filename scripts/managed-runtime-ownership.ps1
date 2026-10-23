function Assert-ManagedRuntimeProcessTime([object]$Record,[object]$Snapshot) {
    if($null -eq $Snapshot){return}
    if($null -eq $Record.startedAt -or [double]$Record.startedAt -le 0 -or $null -eq $Snapshot.CreationDate){
        throw 'Refusing stop without process creation and runtime registration timestamps'
    }
    $registeredSeconds=[double]$Record.startedAt
    $wholeSeconds=[long][Math]::Floor($registeredSeconds)
    $registered=[DateTimeOffset]::FromUnixTimeSeconds($wholeSeconds).AddTicks([long](($registeredSeconds-$wholeSeconds)*10000000))
    $created=[DateTimeOffset]([datetime]$Snapshot.CreationDate)
    if($created -gt $registered){throw 'Refusing stop: PID was created after the saved runtime'}
}
