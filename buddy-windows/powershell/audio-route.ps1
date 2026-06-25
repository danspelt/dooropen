param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Phone", "Computer")]
    [string]$Target,
    [string]$HeadsetName = "OpenRun"
)

$ErrorActionPreference = "Stop"

function Invoke-WinRtAsync {
    param($AsyncOp, [Type]$ResultType)
    $method = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq "AsTask" -and $_.IsGenericMethodDefinition -and $_.GetParameters().Count -eq 1
    } | Select-Object -First 1
    if (-not $method) { throw "WinRT AsTask helper not available" }
    $bound = $method.MakeGenericMethod($ResultType)
    $task = $bound.Invoke($null, @($AsyncOp))
    return $task.GetAwaiter().GetResult()
}

function Set-BluetoothRadio {
    param([string]$DesiredState)

    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    [Windows.Devices.Radios.Radio, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null
    [Windows.Devices.Radios.RadioState, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null
    [Windows.Devices.Radios.RadioAccessStatus, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null
    [Windows.Devices.Radios.RadioKind, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null

    $access = Invoke-WinRtAsync ([Windows.Devices.Radios.Radio]::RequestAccessAsync()) ([Windows.Devices.Radios.RadioAccessStatus])
    if ($access -ne [Windows.Devices.Radios.RadioAccessStatus]::Allowed) {
        throw "Bluetooth access denied ($access)"
    }

    $listType = [System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]]
    $radios = Invoke-WinRtAsync ([Windows.Devices.Radios.Radio]::GetRadiosAsync()) $listType
    $bt = $radios | Where-Object { $_.Kind -eq [Windows.Devices.Radios.RadioKind]::Bluetooth } | Select-Object -First 1
    if (-not $bt) { throw "No Bluetooth radio found" }

    $targetState = if ($DesiredState -eq "On") {
        [Windows.Devices.Radios.RadioState]::On
    } else {
        [Windows.Devices.Radios.RadioState]::Off
    }

    if ($bt.State -eq $targetState) { return "already" }

    $result = Invoke-WinRtAsync ($bt.SetStateAsync($targetState)) ([Windows.Devices.Radios.RadioAccessStatus])
    if ($result -ne [Windows.Devices.Radios.RadioAccessStatus]::Allowed) {
        throw "Bluetooth toggle failed ($result)"
    }
    return "ok"
}

if ($Target -eq "Phone") {
    $bt = Set-BluetoothRadio -DesiredState "Off"
    Write-Output "phone|bluetooth-$bt"
    exit 0
}

$bt = Set-BluetoothRadio -DesiredState "On"
Start-Sleep -Seconds 3
Write-Output "computer|bluetooth-$bt"
