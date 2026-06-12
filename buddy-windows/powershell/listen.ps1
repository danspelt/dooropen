# Continuous speech recognition for Buddy wake phrases on Windows.
# Emits one JSON line per recognized command to stdout.

Add-Type -AssemblyName System.Speech

$recognizer = New-Object System.Speech.Recognition.SpeechRecognitionEngine
$recognizer.SetInputToDefaultAudioDevice()

$choices = New-Object System.Speech.Recognition.Choices
$choices.Add("buddy phone")
$choices.Add("buddy, phone")
$choices.Add("buddy computer")
$choices.Add("buddy, computer")
$choices.Add("buddy hang up")
$choices.Add("buddy, hang up")

$grammar = New-Object System.Speech.Recognition.GrammarBuilder
$grammar.Culture = [System.Globalization.CultureInfo]::GetCultureInfo("en-US")
$grammar.Append($choices)
$recognizer.LoadGrammar((New-Object System.Speech.Recognition.Grammar($grammar)))

$recognizer.Add_SpeechRecognized({
    param($sender, $e)
    if ($e.Result.Confidence -lt 0.45) { return }
    $text = $e.Result.Text.Trim().ToLower()
    $payload = @{ recognized = $text } | ConvertTo-Json -Compress
    [Console]::Out.WriteLine($payload)
    [Console]::Out.Flush()
})

$recognizer.RecognizeAsync([System.Speech.Recognition.RecognizeMode]::Multiple)

try {
    while ($true) { Start-Sleep -Seconds 3600 }
} finally {
    $recognizer.RecognizeAsyncStop()
    $recognizer.Dispose()
}
