param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$utf8 = [System.Text.UTF8Encoding]::new($false)
$files = git -C $Root grep -Il -- "Morphe" -- "app/src/main/res/**/strings.xml"

foreach ($relative in $files) {
    $path = Join-Path $Root $relative
    $text = [System.IO.File]::ReadAllText($path)
    $updated = $text.Replace("Morphe Manager", "AutoPatch Hub").Replace("Morphe", "AutoPatch Hub")
    if ($updated -ne $text) {
        [System.IO.File]::WriteAllText($path, $updated, $utf8)
    }
}

Write-Output "Rebranded $($files.Count) Android resource files."
