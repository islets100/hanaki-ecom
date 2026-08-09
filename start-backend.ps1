$ErrorActionPreference = "Stop"

# Resolve the repository root from this script, so the command works no matter
# which directory the caller is currently using.
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = Join-Path $projectRoot ".env"

# Secrets are loaded only into this process. The ignored .env file is never
# printed or copied into application source and build artifacts.
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env. Copy .env.example to .env and set AI_DASHSCOPE_API_KEY."
}

Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
        $parts = $line.Split("=", 2)
        [Environment]::SetEnvironmentVariable(
            $parts[0].Trim(),
            $parts[1].Trim(),
            "Process"
        )
    }
}

if ([string]::IsNullOrWhiteSpace($env:AI_DASHSCOPE_API_KEY) -or
    $env:AI_DASHSCOPE_API_KEY.Contains("your-dashscope-key")) {
    throw "AI_DASHSCOPE_API_KEY is not configured in .env."
}

# Export completed spans to the local OpenTelemetry Collector by default.
# Export is asynchronous and does not block normal customer-service replies.
if ([string]::IsNullOrWhiteSpace($env:OTEL_EXPORT_ENABLED)) {
    $env:OTEL_EXPORT_ENABLED = "true"
}
if ([string]::IsNullOrWhiteSpace($env:OTEL_EXPORTER_OTLP_TRACES_ENDPOINT)) {
    $env:OTEL_EXPORTER_OTLP_TRACES_ENDPOINT = "http://localhost:4318/v1/traces"
}

# 优先使用当前 Windows 用户已经下载好的 Maven Wrapper 发行版。这样即使启动器所在环境
# 临时覆盖了 USERPROFILE/HOME，也不会错误地尝试在 C:\.m2 下重新下载 Maven。
# Resolve the real Windows user profile so Maven uses the writable user cache.
$windowsProfile = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
if ([string]::IsNullOrWhiteSpace($windowsProfile)) { $windowsProfile = $env:USERPROFILE }
if ([string]::IsNullOrWhiteSpace($windowsProfile)) { $windowsProfile = "$env:HOMEDRIVE$env:HOMEPATH" }
if ([string]::IsNullOrWhiteSpace($windowsProfile)) { throw "Unable to resolve the Windows user profile directory." }
$installedMaven = Get-ChildItem -Path (Join-Path $windowsProfile ".m2\wrapper\dists") `
    -Recurse -Filter "mvn.cmd" -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
$mavenRepository = Join-Path $windowsProfile ".m2\repository"

# Run Maven from the backend module that contains pom.xml. A fully qualified
# goal avoids plugin-prefix lookup differences between Maven installations.
Push-Location (Join-Path $projectRoot "server")
try {
    $springBootGoal = "org.springframework.boot:spring-boot-maven-plugin:3.5.8:run"
    if ($installedMaven) {
        & $installedMaven "-Dmaven.repo.local=$mavenRepository" "-Dmaven.test.skip=true" $springBootGoal
    }
    else {
        & .\mvnw.cmd "-Dmaven.test.skip=true" $springBootGoal
    }
    $mavenExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}

exit $mavenExitCode
