#!/bin/bash
set -e

echo "Starting C02 (RabbitMQ) with metrics collection..."

(
    while true; do
        sleep 30
        /usr/local/bin/collect_metrics.sh "C02" "${C05_API_URL:-http://c05:3000}"
    done
) &

exec docker-entrypoint.sh "$@"