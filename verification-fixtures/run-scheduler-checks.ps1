$ErrorActionPreference = 'Stop'
$abJava = 'D:\Android\Android Studio\jbr\bin\java.exe'
$abKotlinHome = 'D:\Android\Android Studio\plugins\Kotlin\kotlinc'
$abRoot = $PSScriptRoot
New-Item -ItemType Directory -Path (Join-Path $abRoot 'build') -Force | Out-Null
& $abJava -cp (Join-Path $abKotlinHome 'lib\*') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -kotlin-home $abKotlinHome (Join-Path $abRoot 'contract\VerificationEngine.kt') (Join-Path $abRoot 'contract\VerificationScheduler.kt') (Join-Path $abRoot 'contract\SchedulerChecks.kt') -include-runtime -d (Join-Path $abRoot 'build\scheduler-checks.jar')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $abJava -jar (Join-Path $abRoot 'build\scheduler-checks.jar') 2>&1 | Tee-Object -FilePath (Join-Path $abRoot 'evidence\scheduler-checks.txt')
exit $LASTEXITCODE
