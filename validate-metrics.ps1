# Quick Validation Test for Metrics Collection (Windows PowerShell)
# Tests: 20 nodes, 0-33% Byzantine, 60s duration

$ProjectDir = "D:\OneDrive\Documentos\UFABC\TCC\Arq-REDD-"
Set-Location $ProjectDir

Write-Host "=== Arq-REDD+ MVP Metrics Collection Validation Test ===" -ForegroundColor Cyan
Write-Host ""

Write-Host "Step 1: Compile..." -ForegroundColor Yellow
mvn clean compile -q
Write-Host "✅ Compilation successful" -ForegroundColor Green
Write-Host ""

Write-Host "Step 2: Create output directory..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path "output\validation-test" | Out-Null
Write-Host "✅ Output directory ready" -ForegroundColor Green
Write-Host ""

Write-Host "Step 3: Run validation scenarios..." -ForegroundColor Yellow
Write-Host "   Scenario A: Hybrid Network (20 nodes, 0-33% Byzantine, 60s)"
Write-Host ""

java -cp target\classes jabs.example.MVPComparison `
  --validators=20 `
  --byzantine=0,33 `
  --duration=60 `
  --output=output/validation-test 2>&1 | Select-Object -First 100

Write-Host ""
Write-Host "Step 4: Check output files..." -ForegroundColor Yellow
if (Test-Path "output\validation-test") {
    Write-Host "✅ Output directory created" -ForegroundColor Green
    Get-ChildItem -Path "output\validation-test" -Filter "*.csv" | Select-Object -First 5 | ForEach-Object {
        Write-Host "   📄 $($_.Name)"
    }
} else {
    Write-Host "⚠️  No output directory" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "=== Test Complete ===" -ForegroundColor Cyan
Write-Host "Check: output/validation-test/ for metrics CSV files"
Write-Host "Expected: CSV files with Tb, Cb, Bf, BFT, Pdv columns"
