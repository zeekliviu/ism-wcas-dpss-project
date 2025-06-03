FROM critoma/amd64_u24_noble_ism_security:latest

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get clean && \
    apt-get update -o Acquire::Retries=3 -o Acquire::http::Timeout="10" && \
    apt-get install -y --no-install-recommends \
    maven \
    openjdk-17-jdk \
    openmpi-bin \
    openmpi-common \
    openssh-client \
    openssh-server \
    build-essential \
    unzip \
    gosu \
    netcat-openbsd \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

COPY helpers/collect_metrics.sh /usr/local/bin/collect_metrics.sh
RUN chmod +x /usr/local/bin/collect_metrics.sh

ARG USERNAME=mpiuser
ARG USER_UID=1000
ARG USER_GID=$USER_UID

RUN \
    TARGET_USERNAME=${USERNAME} && \
    TARGET_UID=${USER_UID} && \
    TARGET_GID=${USER_GID} && \
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

COPY c03/ssh_setup/id_rsa /home/mpiuser/.ssh/id_rsa
COPY c03/ssh_setup/authorized_keys /home/mpiuser/.ssh/authorized_keys

RUN chown mpiuser:mpiuser /home/mpiuser/.ssh/id_rsa /home/mpiuser/.ssh/authorized_keys && \
    chmod 600 /home/mpiuser/.ssh/id_rsa && \
    chmod 600 /home/mpiuser/.ssh/authorized_keys # Changed to 600 for security

RUN echo "" >> /etc/ssh/sshd_config && \
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

RUN ssh-keygen -y -f /home/mpiuser/.ssh/id_rsa > /home/mpiuser/.ssh/id_rsa.pub && \
    mkdir -p /home/mpiuser/.ssh && \
    echo "c03,172.20.0.3 $(cat /etc/ssh/ssh_host_rsa_key.pub)" > /home/mpiuser/.ssh/known_hosts && \
    echo "c04,172.20.0.4 $(cat /etc/ssh/ssh_host_rsa_key.pub)" >> /home/mpiuser/.ssh/known_hosts && \
    echo "localhost,127.0.0.1 $(cat /etc/ssh/ssh_host_rsa_key.pub)" >> /home/mpiuser/.ssh/known_hosts && \
    chmod 644 /home/mpiuser/.ssh/known_hosts && \
    chown mpiuser:mpiuser /home/mpiuser/.ssh/known_hosts /home/mpiuser/.ssh/id_rsa.pub

EXPOSE 22

WORKDIR /opt/app

COPY c03/pom.xml ./

COPY c03/src ./src

RUN mvn clean package -DskipTests

COPY c03/native_app /home/mpiuser/app/native_app

RUN mkdir -p /home/mpiuser/app

RUN mpic++ -fopenmp -o /home/mpiuser/app/process_image_mpi /home/mpiuser/app/native_app/process_image.cpp -lcrypto

RUN chmod +x /home/mpiuser/app/process_image_mpi

RUN cp /opt/app/target/c03-consumer-1.0-SNAPSHOT.jar /home/mpiuser/app/c03-consumer-1.0-SNAPSHOT.jar

RUN chown -R mpiuser:mpiuser /home/mpiuser/app

COPY c03/entrypoint.sh /opt/app/entrypoint.sh
RUN chmod +x /opt/app/entrypoint.sh

ENTRYPOINT ["/opt/app/entrypoint.sh"]

WORKDIR /home/mpiuser/app

RUN echo "--- MANIFEST.MF ---" && unzip -p /home/mpiuser/app/c03-consumer-1.0-SNAPSHOT.jar META-INF/MANIFEST.MF && echo "--- JAR ROOT ---" && unzip -l /home/mpiuser/app/c03-consumer-1.0-SNAPSHOT.jar | head -n 15

CMD ["java", "-jar", "c03-consumer-1.0-SNAPSHOT.jar"]