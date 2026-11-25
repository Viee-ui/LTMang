#!/bin/bash
# Biên dịch toàn bộ project vào thư mục out/
cd "$(dirname "$0")"
mkdir -p out
javac -encoding UTF-8 -d out $(find src -name "*.java") && echo "Biên dịch thành công."
