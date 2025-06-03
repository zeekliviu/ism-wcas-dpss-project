#!/bin/bash

CONTAINER_ID=${1:-"unknown"}
C05_API_URL=${2:-"http://c05:3000"}

get_os_name() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        echo "$PRETTY_NAME"
    else
        echo "Unknown Linux"
    fi
}

get_cpu_usage() {
    cpu_line=$(head -n1 /proc/stat)
    cpu_times=($cpu_line)
    idle_time=${cpu_times[4]}
    total_time=0
    for time in "${cpu_times[@]:1:10}"; do
        total_time=$((total_time + time))
    done
    
    sleep 1
    
    cpu_line=$(head -n1 /proc/stat)
    cpu_times=($cpu_line)
    idle_time2=${cpu_times[4]}
    total_time2=0
    for time in "${cpu_times[@]:1:10}"; do
        total_time2=$((total_time2 + time))
    done
    
    idle_diff=$((idle_time2 - idle_time))
    total_diff=$((total_time2 - total_time))
    
    if [ $total_diff -eq 0 ]; then
        echo "0"
    else
        cpu_usage=$((100 - (100 * idle_diff / total_diff)))
        echo "$cpu_usage"
    fi
}

get_ram_usage() {
    mem_total=$(grep '^MemTotal:' /proc/meminfo | awk '{print $2}')
    mem_available=$(grep '^MemAvailable:' /proc/meminfo | awk '{print $2}')
    
    if [ -z "$mem_available" ]; then
        mem_free=$(grep '^MemFree:' /proc/meminfo | awk '{print $2}')
        mem_buffers=$(grep '^Buffers:' /proc/meminfo | awk '{print $2}')
        mem_cached=$(grep '^Cached:' /proc/meminfo | awk '{print $2}')
        mem_available=$((mem_free + mem_buffers + mem_cached))
    fi
    
    mem_used=$((mem_total - mem_available))
    ram_usage=$((100 * mem_used / mem_total))
    echo "$ram_usage"
}

get_hostname() {
    hostname
}

OS_NAME=$(get_os_name)
CPU_USAGE=$(get_cpu_usage)
RAM_USAGE=$(get_ram_usage)
HOSTNAME=$(get_hostname)
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")

JSON_PAYLOAD=$(cat <<EOF
{
    "containerId": "$CONTAINER_ID",
    "hostname": "$HOSTNAME",
    "osName": "$OS_NAME",
    "cpuUsage": $CPU_USAGE,
    "ramUsage": $RAM_USAGE,
    "timestamp": "$TIMESTAMP"
}
EOF
)

echo "Sending metrics for $CONTAINER_ID: CPU=${CPU_USAGE}%, RAM=${RAM_USAGE}%"

curl -s -X POST \
    -H "Content-Type: application/json" \
    -d "$JSON_PAYLOAD" \
    "$C05_API_URL/api/snmp" || echo "Failed to send metrics to $C05_API_URL"