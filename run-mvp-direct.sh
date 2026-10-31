#!/bin/bash
# Direct MVP Execution (bypasses exec plugin issues)

cd "$(dirname "$0")"

echo "Step 1: Compiling..."
mvn clean compile -q
if [ $? -ne 0 ]; then
    echo "❌ Compilation failed"
    exit 1
fi

echo "Step 2: Building JAR..."
mvn package -q -DskipTests
if [ $? -ne 0 ]; then
    echo "❌ Package failed"
    exit 1
fi

echo "Step 3: Running MVP Comparison..."
java -cp "target/classes:target/jabs-0.2.0.jar" jabs.example.MVPComparison "$@"
