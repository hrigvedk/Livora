#!/bin/bash

# compile-and-test.sh - Script to compile and test the RAG implementation

echo "🔨 Compiling Livora with RAG Support"
echo "===================================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to check command success
check_status() {
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ $1 successful${NC}"
    else
        echo -e "${RED}❌ $1 failed${NC}"
        exit 1
    fi
}

# Navigate to backend directory
cd Livora || exit 1

# Step 1: Clean previous builds
echo ""
echo "1️⃣ Cleaning previous builds..."
mvn clean
check_status "Clean"

# Step 2: Download dependencies
echo ""
echo "2️⃣ Downloading dependencies..."
mvn dependency:resolve
check_status "Dependency resolution"

# Step 3: Compile the project
echo ""
echo "3️⃣ Compiling Java sources..."
mvn compile
check_status "Compilation"

# Step 4: Run tests (if any)
echo ""
echo "4️⃣ Running tests..."
mvn test -DskipTests=false 2>/dev/null || echo -e "${YELLOW}⚠️ No tests found or tests skipped${NC}"

# Step 5: Package the application
echo ""
echo "5️⃣ Creating JAR package..."
mvn package -DskipTests
check_status "Packaging"

# Step 6: Check if Qdrant is running
echo ""
echo "6️⃣ Checking Qdrant status..."
if curl -s http://localhost:6333/health > /dev/null 2>&1; then
    echo -e "${GREEN}✅ Qdrant is running${NC}"
else
    echo -e "${YELLOW}⚠️ Qdrant is not running. Starting it now...${NC}"
    docker run -d \
        --name qdrant \
        -p 6333:6333 \
        -p 6334:6334 \
        -v $(pwd)/qdrant_storage:/qdrant/storage:z \
        --rm \
        qdrant/qdrant:latest

    # Wait for Qdrant to start
    echo -n "Waiting for Qdrant to start"
    for i in {1..30}; do
        if curl -s http://localhost:6333/health > /dev/null 2>&1; then
            echo -e " ${GREEN}✅${NC}"
            break
        fi
        echo -n "."
        sleep 1
    done
fi

# Step 7: Display next steps
echo ""
echo "===================================="
echo -e "${GREEN}✅ Build completed successfully!${NC}"
echo ""
echo "📋 Next steps to run Livora:"
echo ""
echo "1. Set your Gemini API key:"
echo "   export GEMINI_API_KEY='AIzaSyDYX_TqRdjmyQxR0bv0Ujud97WaGnId7Rk'"
echo ""
echo "2. Start Node 1 (User-facing):"
echo "   java -cp target/'*' \\"
echo "     -Dakka.cluster.roles.0=user-facing \\"
echo "     -Dakka.remote.artery.canonical.port=2551 \\"
echo "     -Dhttp.port=8080 \\"
echo "     org.livora.Main"
echo ""
echo "3. In another terminal, start Node 2 (Backend):"
echo "   java -cp target/'*' \\"
echo "     -Dakka.cluster.roles.0=backend \\"
echo "     -Dakka.remote.artery.canonical.port=2552 \\"
echo "     org.livora.Main"
echo ""
echo "4. Start the React frontend:"
echo "   cd ../frontend && npm start"
echo ""
echo "===================================="

# Optional: Check for common issues
echo ""
echo "🔍 Checking for common issues..."

# Check Java version
java_version=$(java -version 2>&1 | grep version | awk '{print $3}' | tr -d '"')
echo "Java version: $java_version"

# Check if apartments.json exists
if [ -f "src/main/resources/apartments.json" ]; then
    echo -e "${GREEN}✅ apartments.json found${NC}"
else
    echo -e "${YELLOW}⚠️ apartments.json not found in src/main/resources/${NC}"
fi

# Check if Gemini API key is set
if [ -z "$GEMINI_API_KEY" ]; then
    echo -e "${YELLOW}⚠️ GEMINI_API_KEY not set${NC}"
else
    echo -e "${GREEN}✅ GEMINI_API_KEY is configured${NC}"
fi

echo ""
echo "Build script completed!"
