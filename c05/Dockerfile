FROM critoma/amd64_u24_noble_ism_security:latest

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    mysql-server supervisor curl gnupg ca-certificates wget && \
    dpkgArch="$(dpkg --print-architecture)" && \
    case "${dpkgArch##*-}" in \
    amd64|arm64) \
    GOSU_VERSION=1.17; \
    wget -O /usr/local/bin/gosu "https://github.com/tianon/gosu/releases/download/$GOSU_VERSION/gosu-$dpkgArch"; \
    chmod +x /usr/local/bin/gosu; \
    gosu --version; \
    ;; \
    *) echo >&2 "error: unsupported architecture for gosu: '$dpkgArch'"; exit 1 ;; \
    esac && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    curl -fsSL https://pgp.mongodb.com/server-7.0.asc | tee /etc/apt/trusted.gpg.d/mongodb.asc && \
    echo "deb [arch=amd64 signed-by=/etc/apt/trusted.gpg.d/mongodb.asc] \
    https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/7.0 multiverse" \
    > /etc/apt/sources.list.d/mongodb.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends mongodb-org && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

COPY helpers/collect_metrics.sh /usr/local/bin/collect_metrics.sh
RUN chmod +x /usr/local/bin/collect_metrics.sh

RUN useradd -m -s /usr/sbin/nologin nodeapp

COPY c05/db/mysql-init/*.sql /docker-entrypoint-initdb.d/
COPY c05/run_mysql_and_init.sh /usr/local/bin/run_mysql_and_init.sh
COPY c05/mysql_custom.cnf /etc/mysql/conf.d/custom.cnf

RUN set -eux; \
    mkdir -p /var/lib/mysql /var/run/mysqld /var/log/mysql; \
    chown -R mysql:mysql /var/lib/mysql /var/run/mysqld /var/log/mysql; \
    chmod -R 755 /docker-entrypoint-initdb.d; \
    chmod +x /usr/local/bin/run_mysql_and_init.sh; \
    chmod 644 /etc/mysql/conf.d/custom.cnf; \
    chown root:root /etc/mysql/conf.d/custom.cnf

COPY c05/api /opt/app

RUN mkdir -p /opt/app/mysql_shared_uploads && \
    chown -R nodeapp:nodeapp /opt/app/mysql_shared_uploads && \
    chown -R nodeapp:nodeapp /opt/app

USER nodeapp
WORKDIR /opt/app
RUN npm install --omit=dev

USER root
COPY c05/supervisord.conf /etc/supervisor/conf.d/supervisord.conf
RUN mkdir -p /data/db && chown -R mongodb:mongodb /data/db

EXPOSE 3000 3306
CMD ["/usr/bin/supervisord","-c","/etc/supervisor/conf.d/supervisord.conf"]