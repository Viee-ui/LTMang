#!/bin/bash
cd "$(dirname "$0")"
java -cp out server.TrackerServer "$@"
