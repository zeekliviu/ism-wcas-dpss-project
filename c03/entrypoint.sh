#!/bin/bash
set -e
set -x

echo "C03 Container starting..."

/usr/sbin/sshd -D &

(
    while true; do
        sleep 30
        /usr/local/bin/collect_metrics.sh "C03" "${C05_API_URL:-http://c05:3000}"
    done
) &

sleep 10

mkdir -p /run/sshd
chmod 0755 /run/sshd

echo "Starting SSH server as root..."
/usr/sbin/sshd -D -e &> /tmp/sshd_log.txt &
SSHD_PID=$!

sleep 2
if ! ps -p $SSHD_PID > /dev/null; then
    echo "ERROR: sshd failed to start. Check SSH server logs."
    echo "Dumping /tmp/sshd_log.txt:"
    cat /tmp/sshd_log.txt
    if [ -f /var/log/auth.log ]; then
        echo "Dumping /var/log/auth.log:"
        cat /var/log/auth.log
    fi
    exit 1
fi
echo "SSHD process started with PID $SSHD_PID. Verifying port 22..."

for i in {1..10}; do
    if netstat -tulnp | grep ':22' > /dev/null; then
        echo "SSHD is listening on port 22."
        break
    else
        echo "Waiting for SSHD to listen on port 22 (attempt $i/10)..."
        sleep 1
    fi
done

if ! netstat -tulnp | grep ':22' > /dev/null; then
    echo "ERROR: SSHD is not listening on port 22 after 10 seconds."
    echo "Current network listeners:"
    netstat -tulnp
    echo "Dumping /tmp/sshd_log.txt:"
    cat /tmp/sshd_log.txt
    if [ -f /var/log/auth.log ]; then
        echo "Dumping /var/log/auth.log:"
        cat /var/log/auth.log
    fi
    exit 1
fi

echo "Verifying mpiuser .ssh directory and file permissions..."
chown -R mpiuser:mpiuser /home/mpiuser/.ssh
chmod 700 /home/mpiuser/.ssh
chmod 600 /home/mpiuser/.ssh/id_rsa
chmod 600 /home/mpiuser/.ssh/authorized_keys
chmod 644 /home/mpiuser/.ssh/known_hosts 2>/dev/null || true

echo "Waiting for c04 to be available..."
MAX_C04_ATTEMPTS=30
C04_ATTEMPT=1
while [ $C04_ATTEMPT -le $MAX_C04_ATTEMPTS ]; do
    if nc -z c04 22 2>/dev/null; then
        echo "c04 SSH port is open. Attempting to get host key..."
        C04_HOST_KEY=$(ssh-keyscan -t rsa c04 2>/dev/null | head -1)
        if [ ! -z "$C04_HOST_KEY" ]; then
            echo "Successfully retrieved c04 host key: $C04_HOST_KEY"
            sed -i '/c04/d' /home/mpiuser/.ssh/known_hosts 2>/dev/null || true
            echo "$C04_HOST_KEY" >> /home/mpiuser/.ssh/known_hosts
            chown mpiuser:mpiuser /home/mpiuser/.ssh/known_hosts
            chmod 644 /home/mpiuser/.ssh/known_hosts
            break
        fi
    fi
    echo "c04 not ready yet (attempt $C04_ATTEMPT/$MAX_C04_ATTEMPTS)..."
    sleep 2
    C04_ATTEMPT=$((C04_ATTEMPT+1))
done

if [ $C04_ATTEMPT -gt $MAX_C04_ATTEMPTS ]; then
    echo "WARNING: Could not retrieve c04 host key after $MAX_C04_ATTEMPTS attempts. MPI may fail."
fi

echo "Waiting for SSH server to accept connections for mpiuser@localhost..."
MAX_ATTEMPTS=15
ATTEMPT_NUM=1

while ! ssh -i /home/mpiuser/.ssh/id_rsa -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -p 22 mpiuser@localhost exit 2>/dev/null; do
    if [ ${ATTEMPT_NUM} -eq ${MAX_ATTEMPTS} ]; then
        echo "ERROR: SSH server did not become ready for mpiuser@localhost after ${MAX_ATTEMPTS} attempts."
        echo "Attempting verbose SSH connection for diagnostics:"
        ssh -v -i /home/mpiuser/.ssh/id_rsa -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -p 22 mpiuser@localhost exit
        echo "Dumping /tmp/sshd_log.txt:"
        cat /tmp/sshd_log.txt
        if [ -f /var/log/auth.log ]; then
            echo "Dumping /var/log/auth.log:"
            cat /var/log/auth.log
        fi
        exit 1
    fi
    echo "SSH not ready yet (attempt ${ATTEMPT_NUM}/${MAX_ATTEMPTS}). Sleeping..."
    if ! ps -p $SSHD_PID > /dev/null; then
        echo "ERROR: sshd process (PID $SSHD_PID) died during startup loop."
        echo "Dumping /tmp/sshd_log.txt:"
        cat /tmp/sshd_log.txt
        if [ -f /var/log/auth.log ]; then
            echo "Dumping /var/log/auth.log:"
            cat /var/log/auth.log
        fi
        exit 1
    fi
    sleep 2
    ATTEMPT_NUM=$((ATTEMPT_NUM+1))
done
echo "SSH server is ready and accepting connections for mpiuser@localhost."

echo "Testing SSH connectivity to c04..."
if ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/home/mpiuser/.ssh/known_hosts -o PasswordAuthentication=no -o PreferredAuthentications=publickey -o IdentityFile=/home/mpiuser/.ssh/id_rsa -o ConnectTimeout=10 mpiuser@c04 "echo 'SSH to c04 successful'" 2>/dev/null; then
    echo "SSH connectivity to c04 is working."
else
    echo "WARNING: SSH connectivity to c04 failed. MPI jobs may not work properly."
    echo "Attempting verbose SSH connection for diagnostics:"
    ssh -v -o StrictHostKeyChecking=no -o UserKnownHostsFile=/home/mpiuser/.ssh/known_hosts -o PasswordAuthentication=no -o PreferredAuthentications=publickey -o IdentityFile=/home/mpiuser/.ssh/id_rsa -o ConnectTimeout=10 mpiuser@c04 "echo 'SSH test'" || true
fi

echo "Testing SSH connectivity to localhost..."
if ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/home/mpiuser/.ssh/known_hosts -o PasswordAuthentication=no -o PreferredAuthentications=publickey -o IdentityFile=/home/mpiuser/.ssh/id_rsa -o ConnectTimeout=10 mpiuser@localhost "echo 'SSH to localhost successful'" 2>/dev/null; then
    echo "SSH connectivity to localhost is working."
else
    echo "WARNING: SSH connectivity to localhost failed."
fi

echo "Starting Java application as mpiuser..."
exec gosu mpiuser java -jar /home/mpiuser/app/c03-consumer-1.0-SNAPSHOT.jar