# Direct MVP Execution - PowerShell (bypasses exec plugin issues)

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir

Write-Host "Step 1: Compiling..." -ForegroundColor Yellow
mvn clean compile -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Compilation failed" -ForegroundColor Red
    exit 1
}

Write-Host "Step 2: Building JAR..." -ForegroundColor Yellow
mvn package -q -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Package failed" -ForegroundColor Red
    exit 1
}

Write-Host "Step 3: Running MVP Comparison..." -ForegroundColor Cyan
java -cp "target\classes;target\jabs-0.2.0.jar" jabs.example.MVPComparison $args
