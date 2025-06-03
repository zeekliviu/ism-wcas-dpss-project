#!/bin/bash

echo "Starting C01 with metrics collection..."

(
    while true; do
        sleep 30
        /usr/local/bin/collect_metrics.sh "C01" "http://c05:3000"
    done
) &

exec java -jar ./app.jar