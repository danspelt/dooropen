param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("On", "Off")]
    [string]$State,
    [string]$HeadsetName = ""
)

$ErrorActionPreference = "Stop"

function Invoke-WinRtAsync {
    param(
        $AsyncOp,
        [Type]$ResultType
    )
    $method = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq "AsTask" -and $_.IsGenericMethodDefinition -and $_.GetParameters().Count -eq 1
    } | Select-Object -First 1
    if (-not $method) {
        throw "WinRT AsTask helper not available"
    }
    $bound = $method.MakeGenericMethod($ResultType)
    $task = $bound.Invoke($null, @($AsyncOp))
    return $task.GetAwaiter().GetResult()
}

function Set-BluetoothViaWinRt {
    param([string]$DesiredState)

    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    [Windows.Devices.Radios.Radio, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null
    [Windows.Devices.Radios.RadioState, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null
    [Windows.Devices.Radios.RadioAccessStatus, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null
    [Windows.Devices.Radios.RadioKind, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null

    $access = Invoke-WinRtAsync ([Windows.Devices.Radios.Radio]::RequestAccessAsync()) ([Windows.Devices.Radios.RadioAccessStatus])
    if ($access -ne [Windows.Devices.Radios.RadioAccessStatus]::Allowed) {
        throw "Bluetooth radio access denied ($access)"
    }

    $listType = [System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]]
    $radios = Invoke-WinRtAsync ([Windows.Devices.Radios.Radio]::GetRadiosAsync()) $listType
    $bluetooth = $radios | Where-Object { $_.Kind -eq [Windows.Devices.Radios.RadioKind]::Bluetooth } | Select-Object -First 1
    if (-not $bluetooth) {
        throw "No Bluetooth radio found"
    }

    $target = if ($DesiredState -eq "On") {
        [Windows.Devices.Radios.RadioState]::On
    } else {
        [Windows.Devices.Radios.RadioState]::Off
    }

    if ($bluetooth.State -eq $target) {
        return "already"
    }

    $result = Invoke-WinRtAsync ($bluetooth.SetStateAsync($target)) ([Windows.Devices.Radios.RadioAccessStatus])
    if ($result -ne [Windows.Devices.Radios.RadioAccessStatus]::Allowed) {
        throw "SetState failed ($result)"
    }
    return "ok"
}

function Get-BluetoothRadioPnP {
    Get-PnpDevice -ErrorAction SilentlyContinue | Where-Object {
        $_.FriendlyName -match 'Wireless Bluetooth|Bluetooth Adapter|Bluetooth Radio' -and
        $_.InstanceId -match '^USB\\'
    } | Select-Object -First 1
}

function Set-HeadsetDevicesViaPnP {
    param([string]$DesiredState, [string]$Name)

    if ([string]::IsNullOrWhiteSpace($Name)) { return $false }

    $devices = @(Get-PnpDevice -ErrorAction SilentlyContinue | Where-Object {
        $_.FriendlyName -like "*$Name*" -and $_.InstanceId -match '^BTH'
    })
    if ($devices.Count -eq 0) { return $false }

    foreach ($dev in $devices) {
        if ($DesiredState -eq "Off" -and $dev.Status -eq "OK") {
            Disable-PnpDevice -InstanceId $dev.InstanceId -Confirm:$false -ErrorAction Stop
        } elseif ($DesiredState -eq "On" -and $dev.Status -ne "OK") {
            Enable-PnpDevice -InstanceId $dev.InstanceId -Confirm:$false -ErrorAction Stop
        }
    }
    return $true
}

function Set-BluetoothViaPnP {
    param([string]$DesiredState)

    $radio = Get-BluetoothRadioPnP
    if (-not $radio) {
        throw "No Bluetooth adapter found via PnP"
    }

    if ($DesiredState -eq "Off") {
        if ($radio.Status -eq "Error") { return "already" }
        Disable-PnpDevice -InstanceId $radio.InstanceId -Confirm:$false -ErrorAction Stop
    } else {
        Enable-PnpDevice -InstanceId $radio.InstanceId -Confirm:$false -ErrorAction Stop
    }
    return "ok"
}

$result = $null
$errors = @()

# Prefer WinRT radio toggle — works without admin on most PCs.
foreach ($fn in @(
        { Set-BluetoothViaWinRt -DesiredState $State },
        { if (Set-HeadsetDevicesViaPnP -DesiredState $State -Name $HeadsetName) { "ok" } else { throw "No headset devices matched" } },
        { Set-BluetoothViaPnP -DesiredState $State }
    )) {
    try {
        $result = & $fn
        break
    } catch {
        $errors += $_.Exception.Message
    }
}

if (-not $result) {
    throw ($errors -join " | ")
}

Write-Output "${result}_$($State.ToLower())"
