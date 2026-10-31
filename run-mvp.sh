#!/bin/bash
# MVPComparison Execution Script

cd "$(dirname "$0")"

echo "=== Compiling..."
mvn clean compile -q

echo "=== Running MVP Comparison..."
java -cp target/classes jabs.example.MVPComparison "$@"
