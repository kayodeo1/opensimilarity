FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
RUN useradd --system --no-create-home opensim
COPY --from=build /build/target/opensimilarity.jar /app/opensimilarity.jar
USER opensim
# The embedding runtime extracts native libraries under the user's home, which this user does not have.
ENV HOME=/tmp
# Inside the container the app must listen beyond loopback; compose publishes it on the host's loopback only.
ENV OPENSIM_BIND_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/opensimilarity.jar"]
