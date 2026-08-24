# ─── Build stage ────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy only the POM first so Docker can cache the dependency download layer
# separately from source changes — much faster rebuilds.
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Now copy the rest of the source and build the jar.
COPY src ./src
RUN mvn -B clean package -DskipTests

# ─── Runtime stage ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Render sets $PORT at runtime; Spring Boot must bind to it.
ENV SERVER_PORT=${PORT}

# Copy the built jar from the build stage. Wildcard handles whatever the
# artifactId/version in pom.xml produces without hardcoding a filename.
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${PORT:-8080}"]