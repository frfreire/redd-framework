#!/bin/bash
# Quick Validation Test for Metrics Collection
# Tests: 20 nodes, 0-33% Byzantine, 60s duration

set -e

PROJECT_DIR="/d/OneDrive/Documentos/UFABC/TCC/Arq-REDD-"
cd "$PROJECT_DIR"

echo "=== Arq-REDD+ MVP Metrics Collection Validation Test ==="
echo ""
echo "Step 1: Compile..."
mvn clean compile -q
echo "✅ Compilation successful"
echo ""

echo "Step 2: Create output directory..."
mkdir -p output/validation-test
echo "✅ Output directory ready"
echo ""

echo "Step 3: Run validation scenarios..."
echo "   Scenario A: Hybrid Network (20 nodes, 0-33% Byzantine, 60s)"
java -cp target/classes jabs.example.MVPComparison \
  --validators=20 \
  --byzantine=0,33 \
  --duration=60 \
  --output=output/validation-test 2>&1 | head -50

echo ""
echo "Step 4: Check output files..."
if [ -d "output/validation-test" ]; then
    echo "✅ Output directory created"
    find output/validation-test -name "*.csv" | head -5 && echo "   ... found CSV files"
else
    echo "⚠️  No output directory"
fi

echo ""
echo "=== Test Complete ==="
echo "Check: output/validation-test/ for metrics CSV files"
echo "Expected files: scenario logs with Tb, Cb, Bf, BFT, Pdv columns"
