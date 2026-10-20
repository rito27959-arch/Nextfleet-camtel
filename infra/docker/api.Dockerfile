# NexFleet — API Spring Boot. Construit depuis la RACINE du dépôt : docker build -f infra/docker/api.Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY services services
COPY apps/desktop-javafx/pom.xml apps/desktop-javafx/pom.xml
COPY infra/db/migrations infra/db/migrations
RUN mvn -B -ntp -q -pl services/nextfleet-api -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 nexfleet
WORKDIR /app
COPY --from=build /src/services/nextfleet-api/target/nextfleet-api-*.jar /app/api.jar
USER nexfleet
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError" PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/api.jar"]
