FROM node:20-alpine AS frontend-builder
WORKDIR /app/frontend
COPY c01/frontend/package.json c01/frontend/package-lock.json* ./
RUN npm ci
COPY c01/frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend-builder
WORKDIR /app/backend
COPY c01/backend/pom.xml ./

COPY c01/backend/src ./src/

COPY --from=frontend-builder /app/frontend/dist ./src/main/resources/static/

RUN mvn package -DskipTests

FROM critoma/amd64_u24_noble_ism_security:latest

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y openjdk-17-jre-headless ca-certificates curl && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

COPY helpers/collect_metrics.sh /usr/local/bin/collect_metrics.sh
RUN chmod +x /usr/local/bin/collect_metrics.sh

WORKDIR /opt/app

COPY --from=backend-builder /app/backend/target/backend-1.0.jar ./app.jar

COPY c01/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

EXPOSE 7000

CMD ["/usr/local/bin/entrypoint.sh"]