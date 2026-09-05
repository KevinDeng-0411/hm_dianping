FROM maven:3.8-eclipse-temurin-11 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8081
# Profile is driven by SPRING_PROFILES_ACTIVE env (docker-compose.yml sets docker,bench).
# Keeping it out of the entrypoint lets the bench profile be activated for load tests.
ENTRYPOINT ["java", "-jar", "app.jar"]
