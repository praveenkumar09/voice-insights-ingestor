FROM maven:3.9-eclipse-temurin-23 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:23-jre
WORKDIR /app
COPY --from=build /build/target/voice-insights-ingestor-1.0-SNAPSHOT.jar app.jar
COPY --from=build /build/src/main/resources/input ./src/main/resources/input
ENTRYPOINT ["java", "-jar", "app.jar"]
