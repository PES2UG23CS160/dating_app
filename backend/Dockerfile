FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY settings.xml /root/.m2/settings.xml
COPY src src
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -ntp verify
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S unfold && adduser -S unfold -G unfold
COPY --from=build /app/target/unfold-0.1.0.jar app.jar
USER unfold
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
