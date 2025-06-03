FROM critoma/amd64_u24_noble_ism_security:latest

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && \
    apt-get install -y openmpi-bin libopenmpi-dev libomp5 libomp-dev libssl-dev openssh-server netcat-openbsd && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

COPY helpers/collect_metrics.sh /usr/local/bin/collect_metrics.sh
RUN chmod +x /usr/local/bin/collect_metrics.sh

ARG USERNAME=mpiuser
ARG USER_UID=1000
ARG USER_GID=$USER_UID
RUN \
    TARGET_USERNAME=$USERNAME && \
    TARGET_UID=$USER_UID && \
    TARGET_GID=$USER_GID && \
    if CURRENT_GROUPNAME_AT_GID=$(getent group $TARGET_GID | cut -d: -f1); then \
    if [ "$CURRENT_GROUPNAME_AT_GID" != "$TARGET_USERNAME" ]; then \
    if getent group $TARGET_USERNAME >/dev/null 2>&1 && [ "$(getent group $TARGET_USERNAME | cut -d: -f3)" != "$TARGET_GID" ]; then \
    delgroup $TARGET_USERNAME || true; \
    fi; \
    groupmod -n $TARGET_USERNAME $CURRENT_GROUPNAME_AT_GID; \
    fi; \
    else \
    if getent group $TARGET_USERNAME >/dev/null 2>&1; then \
    groupmod -g $TARGET_GID $TARGET_USERNAME; \
    else \
    groupadd -g $TARGET_GID $TARGET_USERNAME; \
    fi; \
    fi && \
    if CURRENT_USERNAME_AT_UID=$(getent passwd $TARGET_UID | cut -d: -f1); then \
    if [ "$CURRENT_USERNAME_AT_UID" != "$TARGET_USERNAME" ]; then \
    if id -u $TARGET_USERNAME >/dev/null 2>&1 && [ "$(id -u $TARGET_USERNAME)" != "$TARGET_UID" ]; then \
    deluser $TARGET_USERNAME --remove-home || true; \
    fi; \
    usermod -l $TARGET_USERNAME -d /home/$TARGET_USERNAME -m $CURRENT_USERNAME_AT_UID; \
    fi; \
    else \
    if id -u $TARGET_USERNAME >/dev/null 2>&1; then \
    usermod -u $TARGET_UID -d /home/$TARGET_USERNAME -m $TARGET_USERNAME; \
    else \
    useradd -u $TARGET_UID -d /home/$TARGET_USERNAME -m $TARGET_USERNAME; \
    fi; \
    fi && \
    usermod -g $TARGET_USERNAME -s /bin/bash $TARGET_USERNAME && \
    mkdir -p /home/$TARGET_USERNAME/.ssh && \
    chown -R $TARGET_USERNAME:$TARGET_USERNAME /home/$TARGET_USERNAME/.ssh

WORKDIR /opt/app

COPY c04/native_app ./native_app

RUN mkdir -p /opt/app/native

RUN mpic++ -fopenmp -o /opt/app/native/process_image_mpi ./native_app/process_image.cpp -lcrypto
RUN chmod +x /opt/app/native/process_image_mpi
RUN mkdir -p /home/$USERNAME/app && \
    cp /opt/app/native/process_image_mpi /home/$USERNAME/app/ && \
    chown -R $USERNAME:$USERNAME /home/$USERNAME/app

COPY c04/ssh_setup/authorized_keys /home/$USERNAME/.ssh/authorized_keys

RUN chmod 700 /home/$USERNAME/.ssh && \
    chmod 600 /home/$USERNAME/.ssh/authorized_keys && \
    chown -R $USERNAME:$USERNAME /home/$USERNAME/.ssh

RUN mkdir -p /var/run/sshd && \
    echo "" >> /etc/ssh/sshd_config && \
    echo "PasswordAuthentication no" >> /etc/ssh/sshd_config && \
    echo "PubkeyAuthentication yes" >> /etc/ssh/sshd_config && \
    echo "AuthorizedKeysFile      .ssh/authorized_keys" >> /etc/ssh/sshd_config && \
    echo "ChallengeResponseAuthentication no" >> /etc/ssh/sshd_config && \
    echo "UsePAM no" >> /etc/ssh/sshd_config && \
    echo "PermitRootLogin no" >> /etc/ssh/sshd_config && \
    echo "AllowUsers mpiuser" >> /etc/ssh/sshd_config && \
    echo "LogLevel DEBUG" >> /etc/ssh/sshd_config && \
    echo "StrictModes no" >> /etc/ssh/sshd_config && \
    echo "HostKeyAlgorithms +ssh-rsa" >> /etc/ssh/sshd_config && \
    echo "PubkeyAcceptedKeyTypes +ssh-rsa" >> /etc/ssh/sshd_config

RUN ssh-keygen -A

RUN mkdir -p /home/$USERNAME/.ssh && \
    touch /home/$USERNAME/.ssh/known_hosts && \
    chmod 644 /home/$USERNAME/.ssh/known_hosts && \
    chown $USERNAME:$USERNAME /home/$USERNAME/.ssh/known_hosts

COPY c04/entrypoint.sh /opt/app/entrypoint.sh
RUN chmod +x /opt/app/entrypoint.sh

EXPOSE 22

ENTRYPOINT ["/opt/app/entrypoint.sh"]