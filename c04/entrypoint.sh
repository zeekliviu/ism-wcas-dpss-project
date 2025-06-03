#!/bin/bash
set -e
set -x

(
    while true; do
        sleep 30
        /usr/local/bin/collect_metrics.sh "C04" "http://c05:3000"
    done
) &

mkdir -p /run/sshd
chmod 0755 /run/sshd

echo "Setting up SSH known_hosts..."
mkdir -p /home/mpiuser/.ssh

echo "localhost,127.0.0.1 $(cat /etc/ssh/ssh_host_rsa_key.pub)" > /home/mpiuser/.ssh/known_hosts
echo "c04,172.20.0.4 $(cat /etc/ssh/ssh_host_rsa_key.pub)" >> /home/mpiuser/.ssh/known_hosts

echo "# c03 host key will be added at runtime" >> /home/mpiuser/.ssh/known_hosts

chmod 644 /home/mpiuser/.ssh/known_hosts
chown mpiuser:mpiuser /home/mpiuser/.ssh/known_hosts

echo "Verifying mpiuser .ssh directory and file permissions..."
chown -R mpiuser:mpiuser /home/mpiuser/.ssh
chmod 700 /home/mpiuser/.ssh
chmod 600 /home/mpiuser/.ssh/authorized_keys
chmod 644 /home/mpiuser/.ssh/known_hosts

echo "Starting SSH server..."
exec /usr/sbin/sshd -D -e