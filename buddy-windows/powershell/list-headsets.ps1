# List likely headset / Bluetooth audio devices for the UI dropdown.
$ErrorActionPreference = "SilentlyContinue"

$names = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)

Get-PnpDevice -PresentOnly | Where-Object {
    ($_.Class -eq 'Bluetooth' -or $_.Class -eq 'Media' -or $_.Class -eq 'AudioEndpoint') -and
    $_.FriendlyName -notmatch 'Radio|Enumerator|Transport|Profile|GATT|LE|Microsoft Bluetooth|Bluetooth Device|A2DP SNK|Avrcp|Generic|Intel|Realtek|NVIDIA|AMD|USB Audio|High Definition Audio|Service$|Service |Gateway|Proxy|NAP |Object Push|Phonebook|Sim Access|Display Audio|HD Audio Driver|Microphone \(|BenQ|Logitech BRIO|Pixel 7 Pro'
} | ForEach-Object {
    [void]$names.Add($_.FriendlyName)
}

Get-CimInstance -ClassName Win32_SoundDevice -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.Name) { [void]$names.Add($_.Name) }
}

$sorted = @($names | Sort-Object {
    $n = $_.ToLowerInvariant()
    $score = 0
    if ($n -match 'headset') { $score += 4 }
    if ($n -match 'bluetooth') { $score += 3 }
    if ($n -match 'buds|airpods|wh-|sony|bose|jabra') { $score += 2 }
    -$score
}, { $_ })

$headsets = @($sorted | ForEach-Object { @{ id = $_; name = $_ } })
@{ headsets = $headsets } | ConvertTo-Json -Compress
