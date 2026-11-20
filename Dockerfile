FROM node:22-alpine AS web
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build
FROM maven:3.9-eclipse-temurin-21 AS java
WORKDIR /app
COPY backend/pom.xml ./
COPY backend/settings.xml /root/.m2/settings.xml
COPY backend/src ./src
COPY --from=web /app/dist ./src/main/resources/static
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -ntp verify
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S unfold && adduser -S unfold -G unfold
COPY --from=java /app/target/unfold-0.1.0.jar app.jar
USER unfold
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -Djava.awt.headless=true"
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
