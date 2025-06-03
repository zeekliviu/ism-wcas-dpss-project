#!/bin/bash
set -e
set -x
set -o pipefail

MYSQL_DATA_DIR="/var/lib/mysql"
MYSQL_RUN_DIR="/var/run/mysqld"
MYSQL_LOG_DIR="/var/log/mysql"
MYSQL_ERROR_LOG="${MYSQL_LOG_DIR}/error.log"
MYSQL_PID_FILE="${MYSQL_RUN_DIR}/mysqld.pid"
MYSQL_SOCKET="${MYSQL_RUN_DIR}/mysqld.sock"

MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-rootpass}"
MYSQLD_SAFE_PID=0

cleanup() {
    echo "INFO: Running cleanup..."
    sync
    if [ "$MYSQLD_SAFE_PID" -ne 0 ] && kill -0 "$MYSQLD_SAFE_PID" 2>/dev/null; then
        echo "INFO: Stopping mysqld_safe (PID $MYSQLD_SAFE_PID)..."
        sync
        kill -SIGTERM "$MYSQLD_SAFE_PID"
        
        for _ in {1..10}; do
            if ! kill -0 "$MYSQLD_SAFE_PID" 2>/dev/null; then
                echo "INFO: mysqld_safe stopped."
                sync
                MYSQLD_SAFE_PID=0
                break
            fi
            sleep 1
        done

        if [ "$MYSQLD_SAFE_PID" -ne 0 ] && kill -0 "$MYSQLD_SAFE_PID" 2>/dev/null; then
            echo "WARN: mysqld_safe did not stop gracefully after 10s. Sending SIGKILL..."
            sync
            kill -SIGKILL "$MYSQLD_SAFE_PID"
            sleep 1
        fi
    else
        echo "INFO: mysqld_safe process (PID $MYSQLD_SAFE_PID) not found or already stopped."
        sync
    fi
    
    if [ -f "$MYSQL_PID_FILE" ]; then
        MYSQLD_PID=$(cat "$MYSQL_PID_FILE")
        if [ -n "$MYSQLD_PID" ] && kill -0 "$MYSQLD_PID" 2>/dev/null; then
            echo "WARN: mysqld (PID $MYSQLD_PID) still running. Attempting to stop."
            sync
            kill -SIGTERM "$MYSQLD_PID"
            sleep 5
            if kill -0 "$MYSQLD_PID" 2>/dev/null; then
                kill -SIGKILL "$MYSQLD_PID"
            fi
        fi
        rm -f "$MYSQL_PID_FILE"
    fi
    echo "INFO: Cleanup finished."
    sync
}

trap 'cleanup' SIGTERM SIGINT EXIT

mkdir -p "$MYSQL_DATA_DIR" "$MYSQL_RUN_DIR" "$MYSQL_LOG_DIR"
chown -R mysql:mysql "$MYSQL_DATA_DIR" "$MYSQL_RUN_DIR" "$MYSQL_LOG_DIR"

touch "$MYSQL_ERROR_LOG" && chown mysql:mysql "$MYSQL_ERROR_LOG"

SHARED_UPLOADS_DIR="/opt/app/mysql_shared_uploads"
echo "INFO: Ensuring ownership and permissions for $SHARED_UPLOADS_DIR"
mkdir -p "$SHARED_UPLOADS_DIR"
chown -R nodeapp:nodeapp "$SHARED_UPLOADS_DIR"
chmod 755 "$SHARED_UPLOADS_DIR"
sync

CUSTOM_CNF_PATH="/etc/mysql/conf.d/custom.cnf"
if [ -f "$CUSTOM_CNF_PATH" ]; then
    echo "INFO: Setting permissions for $CUSTOM_CNF_PATH to 644 and ownership to root:root."
    chown root:root "$CUSTOM_CNF_PATH"
    chmod 644 "$CUSTOM_CNF_PATH"
    echo "INFO: Permissions set for $CUSTOM_CNF_PATH."
else
    echo "WARN: Custom MySQL config $CUSTOM_CNF_PATH not found. Skipping permission setting."
fi
sync

if [ ! -d "${MYSQL_DATA_DIR}/mysql" ]; then
    echo "INFO: MySQL data directory not found or empty. Initializing..."
    sync
    mysqld --initialize-insecure --user=mysql --datadir="$MYSQL_DATA_DIR"
    echo "INFO: MySQL data directory initialized."
    sync
else
    echo "INFO: MySQL data directory already exists. Skipping initialization."
    sync
fi

echo "INFO: Starting mysqld_safe..."
sync
mysqld_safe \
    --user=mysql \
    --datadir="$MYSQL_DATA_DIR" \
    --pid-file="$MYSQL_PID_FILE" \
    --socket="$MYSQL_SOCKET" \
    --log-error="$MYSQL_ERROR_LOG" \
    --max_allowed_packet=10G &

MYSQLD_SAFE_PID=$!
echo "INFO: mysqld_safe started with PID $MYSQLD_SAFE_PID."
sync

echo "INFO: Waiting for MySQL server to start..."
sync
max_retries=60 
count=0
mysql_ready_with_known_password=false
mysql_ready_with_no_password=false

while true; do
    if mysqladmin ping --socket="$MYSQL_SOCKET" -uroot -p"${MYSQL_ROOT_PASSWORD}" --silent; then
        echo "INFO: MySQL server started and accessible via socket (as MySQL root with known password)."
        mysql_ready_with_known_password=true
        sync
        break
    elif mysqladmin ping --socket="$MYSQL_SOCKET" -uroot --silent; then
        echo "INFO: MySQL server started and accessible via socket (as MySQL root, no password or unknown password)."
        mysql_ready_with_no_password=true
        sync
        break
    fi
    
    count=$((count+1))
    if [ "$count" -ge "$max_retries" ]; then
        echo "ERROR: MySQL server did not start within $max_retries seconds."
        sync
        echo "Last 50 lines of MySQL error log (${MYSQL_ERROR_LOG}):"
        tail -n 50 "$MYSQL_ERROR_LOG" || echo "Could not show MySQL error log."
        sync
        exit 1 
    fi
    echo -n "." 
    sleep 1
done

if [ "$mysql_ready_with_no_password" = true ]; then
    echo "INFO: Root user appears to have no password or an unknown password. Attempting to set/reset it for 'root'@'localhost'."
    sync
    mysql_client_output_alter=""
    if ! mysql_client_output_alter=$(mysql --protocol=socket --socket="$MYSQL_SOCKET" -uroot --skip-password \
        -e "ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY '${MYSQL_ROOT_PASSWORD}'; FLUSH PRIVILEGES;" 2>&1); then
        echo "ERROR: Failed to set root password using --skip-password. This can happen if root already had a *different* password."
        echo "MySQL client output (ALTER USER):"
        echo "${mysql_client_output_alter}"
        sync
        if mysqladmin ping --socket="$MYSQL_SOCKET" -uroot -p"${MYSQL_ROOT_PASSWORD}" --silent; then
             echo "INFO: Despite previous error, root password is now the target password. Proceeding."
             sync
        else
            echo "ERROR: Root password is not the target password, and setting it failed. Aborting."
            echo "Last 50 lines of MySQL error log (${MYSQL_ERROR_LOG}):"
            tail -n 50 "$MYSQL_ERROR_LOG" || echo "Could not show MySQL error log."
            sync
            exit 1
        fi
    else
        echo "INFO: Root password set successfully for 'root'@'localhost' using --skip-password."
        sync
    fi
elif [ "$mysql_ready_with_known_password" = true ]; then
    echo "INFO: Root password for 'root'@'localhost' is already correct. Skipping password set."
    sync
else
    echo "CRITICAL: MySQL server readiness could not be determined. Exiting."
    sync
    exit 1
fi

INIT_DB_DIR="/docker-entrypoint-initdb.d"
if [ -d "$INIT_DB_DIR" ] && [ -n "$(ls -A "$INIT_DB_DIR"/*.sql 2>/dev/null)" ]; then
    echo "INFO: Executing SQL scripts from $INIT_DB_DIR..."
    sync
    for f in "$INIT_DB_DIR"/*.sql; do
        echo "INFO: Processing $f..."
        sync
        schema_output=$(mysql --protocol=socket --socket="$MYSQL_SOCKET" -uroot -p"${MYSQL_ROOT_PASSWORD}" < "$f" 2>&1)
        schema_exit_code=$?
        if [ $schema_exit_code -ne 0 ]; then
            echo "ERROR: Failed to execute $f."
            echo "MySQL client exit code: $schema_exit_code"
            echo "MySQL client output: $schema_output"
            sync
            echo "Last 50 lines of MySQL error log (${MYSQL_ERROR_LOG}):"
            tail -n 50 "$MYSQL_ERROR_LOG" || echo "Could not show MySQL error log."
            sync
            exit 1
        fi
        echo "INFO: Successfully executed $f."
        sync
    done
    echo "INFO: All SQL scripts from $INIT_DB_DIR processed."
    sync
else
    echo "INFO: No SQL scripts found in $INIT_DB_DIR or directory does not exist."
    sync
fi

echo "INFO: MySQL initialization complete. Server (mysqld_safe PID $MYSQLD_SAFE_PID) is running."
sync
echo "INFO: This script will now wait for mysqld_safe to exit."
sync

wait "$MYSQLD_SAFE_PID"
exit_status=$?
echo "INFO: mysqld_safe (PID $MYSQLD_SAFE_PID) exited with status $exit_status."
sync

if [ $exit_status -ne 0 ]; then
    echo "ERROR: mysqld_safe exited abnormally with status $exit_status."
    exit $exit_status
fi
exit 0
