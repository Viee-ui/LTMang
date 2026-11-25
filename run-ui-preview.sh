#!/bin/bash
# Xem thử giao diện với dữ liệu giả, không cần Tracker
cd "$(dirname "$0")"
java -cp out client.ui.UiPreview
