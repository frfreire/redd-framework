# MVPComparison Execution Script (PowerShell)

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir

Write-Host "=== Compiling..." -ForegroundColor Yellow
mvn clean compile -q

Write-Host "=== Running MVP Comparison..." -ForegroundColor Cyan
java -cp target\classes jabs.example.MVPComparison $args
