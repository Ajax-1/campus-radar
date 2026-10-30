FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 radar && mkdir -p /app/data && chown -R radar /app
WORKDIR /app
COPY --from=build /src/target/campus-radar.jar app.jar
USER radar
ENV RADAR_BIND_ADDRESS=0.0.0.0
VOLUME /app/data
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
