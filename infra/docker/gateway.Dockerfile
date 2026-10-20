# NexFleet — passerelle GPS (Traccar → NexFleet, téléphones Android à jeton de mission).
# Construit depuis la RACINE du dépôt : docker build -f infra/docker/gateway.Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY services services
COPY apps/desktop-javafx/pom.xml apps/desktop-javafx/pom.xml
COPY infra/db/migrations infra/db/migrations
RUN mvn -B -ntp -q -pl services/gps-traccar-adapter -am package -DskipTests \
 && mvn -B -ntp -q -pl services/gps-traccar-adapter dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/out/lib \
 && cp services/gps-traccar-adapter/target/gps-traccar-adapter-*.jar /out/lib/

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 nexfleet
WORKDIR /app
COPY --from=build /out/lib /app/lib
USER nexfleet
ENV NF_GPS_BIND="[::]:8090" JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8090
ENTRYPOINT ["java", "-cp", "/app/lib/*", "cm.camtel.nextfleet.gps.GpsGatewayMain"]
