[CmdletBinding()]
param(
    [string]$Destination = 'E:\CODEX\Automation\MorpheRecovery',
    [switch]$Start,
    [switch]$InstallPaused
)
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'morphe_codex_guard.py'
if (-not (Test-Path -LiteralPath $source)) { throw 'Guardian source is missing.' }
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$configPath = Join-Path $Destination 'config.json'
$config = [ordered]@{
    repository = 'SYLONG7708/Morphe'
    runtime_root = $Destination
    enabled = -not $InstallPaused
    poll_seconds = 300
    agent_timeout_seconds = 5400
    git = (Get-Command git.exe).Source
    gh = (Get-Command gh.exe).Source
    python = (Get-Command python.exe).Source
    pythonw = (Get-Command pythonw.exe).Source
    codex = (Get-Command codex.exe).Source
    java = (Get-Command java.exe).Source
}
if (Test-Path -LiteralPath $configPath) {
    Copy-Item -LiteralPath $configPath -Destination ($configPath + '.backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
Copy-Item -LiteralPath $source -Destination (Join-Path $Destination 'morphe_codex_guard.py') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'VerifyFile.java') -Destination (Join-Path $Destination 'VerifyFile.java') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\app\src\main\res\raw\update_signing_cert.der') `
    -Destination (Join-Path $Destination 'update_signing_cert.der') -Force
[IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json -Depth 5), $utf8)
# Supervisor restarts the silent worker if it exits. The named mutex prevents
# startup, logon and periodic task launches from creating extra supervisors.
$supervisor = @'
$ErrorActionPreference = 'Stop'
$restartFile = Join-Path $PSScriptRoot 'RESTART'
if (Test-Path -LiteralPath $restartFile) {
    try {
        $statusPath = Join-Path $PSScriptRoot 'status.json'
        $status = Get-Content -LiteralPath $statusPath -Encoding UTF8 -Raw | ConvertFrom-Json
        # Never interrupt an agent or release verification. Resolve the exact
        # installed worker and its supervisor before stopping either process.
        if ($status.phase -in @('paused_by_owner', 'healthy', 'cooldown', 'monitor_error', 'diagnosis_only', 'unresolved', 'workflow_verified')) {
            $worker = Get-CimInstance Win32_Process -Filter ("ProcessId=" + [int]$status.pid)
            $workerScript = Join-Path $PSScriptRoot 'morphe_codex_guard.py'
            if ($worker -and $worker.Name -eq 'pythonw.exe' -and $worker.CommandLine -and $worker.CommandLine.Contains($workerScript)) {
                $parent = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $worker.ParentProcessId)
                $supervisorScript = Join-Path $PSScriptRoot 'Supervise.ps1'
                if ($parent -and $parent.Name -eq 'powershell.exe' -and $parent.CommandLine -and $parent.CommandLine.Contains($supervisorScript)) {
                    Stop-Process -Id $parent.ProcessId -ErrorAction Stop
                    Stop-Process -Id $worker.ProcessId -ErrorAction Stop
                    Remove-Item -LiteralPath $restartFile
                }
            } elseif (-not $worker) { Remove-Item -LiteralPath $restartFile }
        }
    } catch {
        [IO.File]::AppendAllText((Join-Path $PSScriptRoot 'supervisor.log'), ((Get-Date -Format o) + ' Reload deferred: ' + $_.Exception.Message + "`n"))
    }
}
$mutex = [Threading.Mutex]::new($false, 'Local\SyMorpheCodexRecoverySupervisor')
$owned = $false
try {
    try { $owned = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $owned = $true }
    if (-not $owned) { exit 0 }
    while ($true) {
        try {
            $config = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'config.json') -Encoding UTF8 -Raw | ConvertFrom-Json
            $arguments = '"{0}" --config "{1}"' -f (Join-Path $PSScriptRoot 'morphe_codex_guard.py'), (Join-Path $PSScriptRoot 'config.json')
            $child = Start-Process -FilePath $config.pythonw -ArgumentList $arguments -WindowStyle Hidden -PassThru
            $child.WaitForExit()
        } catch {
            [IO.File]::AppendAllText((Join-Path $PSScriptRoot 'supervisor.log'), ((Get-Date -Format o) + ' ' + $_.Exception.Message + "`n"))
        }
        Start-Sleep -Seconds 30
    }
} finally {
    if ($owned) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
'@
[IO.File]::WriteAllText((Join-Path $Destination 'Supervise.ps1'), $supervisor, $utf8)
[IO.File]::WriteAllText((Join-Path $Destination 'RESTART'), (Get-Date -Format o), $utf8)
$runner = @'
Option Explicit
Dim shell, fso, root, command
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
root = fso.GetParentFolderName(WScript.ScriptFullName)
command = """" & shell.ExpandEnvironmentStrings("%WINDIR%\System32\WindowsPowerShell\v1.0\powershell.exe") & """ -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & fso.BuildPath(root, "Supervise.ps1") & """"
shell.Run command, 0, False
'@
$runnerPath = Join-Path $Destination 'StartHidden.vbs'
[IO.File]::WriteAllText($runnerPath, $runner, $utf8)
$wscript = Join-Path $env:WINDIR 'System32\wscript.exe'
$command = '"{0}" //B //NoLogo "{1}"' -f $wscript, $runnerPath
New-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' `
    -Name 'SyMorpheCodexRecovery' -Value $command -PropertyType String -Force | Out-Null
$taskRegistered = $false
$taskError = $null
try {
    $action = New-ScheduledTaskAction -Execute $wscript -Argument ('//B //NoLogo "{0}"' -f $runnerPath)
    $repeat = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(5) -RepetitionInterval (New-TimeSpan -Minutes 5)
    $logon = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $settings = New-ScheduledTaskSettingsSet -Hidden -StartWhenAvailable -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero)
    $principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName 'SyMorpheCodexRecovery' -Action $action -Trigger @($logon, $repeat) `
        -Settings $settings -Principal $principal -Description 'Hidden Morphe workflow diagnosis and verified Codex recovery' `
        -Force -ErrorAction Stop | Out-Null
    $taskRegistered = $true
} catch { $taskError = $_.Exception.Message }
$fallbackTask = $null
# Some Windows accounts can run their existing task but cannot register a new one.
# Reuse its hidden, asynchronous bootstrap without changing any task timing or
# the brain maintenance command. Keep an exact backup for restoration.
if (-not $taskRegistered) {
    $brainRunner = Join-Path $env:USERPROFILE '.codex\tools\run-sync-codex-brain-hidden.vbs'
    $brainTask = Get-ScheduledTask -TaskName 'CodexBrainSessionSync' -ErrorAction SilentlyContinue
    if ($brainTask -and (Test-Path -LiteralPath $brainRunner)) {
        $content = [IO.File]::ReadAllText($brainRunner)
        $marker = "' BEGIN SYMORPHE CODEX RECOVERY"
        if (-not $content.Contains($marker) -and $content.Contains('WScript.Quit shell.Run(command, 0, True)')) {
            Copy-Item -LiteralPath $brainRunner -Destination (Join-Path $Destination ('brain-launcher-before-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.vbs'))
            $bootstrap = @"
' BEGIN SYMORPHE CODEX RECOVERY
On Error Resume Next
shell.Run """$wscript"" //B //NoLogo ""$runnerPath""", 0, False
On Error GoTo 0
' END SYMORPHE CODEX RECOVERY

"@
            $content = $content.Replace('WScript.Quit shell.Run(command, 0, True)',
                $bootstrap + 'WScript.Quit shell.Run(command, 0, True)')
            [IO.File]::WriteAllText($brainRunner, $content, $utf8)
        }
        if ($content.Contains($marker)) { $fallbackTask = 'CodexBrainSessionSync' }
    }
}
$installation = [ordered]@{
    installed_at = (Get-Date -Format o); destination = $Destination
    startup = $command; scheduled_task_registered = $taskRegistered; scheduled_task_error = $taskError
    poll_seconds = 300; requires_logged_in_computer = $true
    fallback_watchdog_task = $fallbackTask
}
[IO.File]::WriteAllText((Join-Path $Destination 'installation.json'), ($installation | ConvertTo-Json -Depth 4), $utf8)
if ($Start) { Start-Process -FilePath $wscript -ArgumentList ('//B //NoLogo "{0}"' -f $runnerPath) -WindowStyle Hidden }
$installation | ConvertTo-Json -Depth 4
