# Build context: repo root (the api module needs the parent pom and the Maven wrapper).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY api/pom.xml api/pom.xml
RUN ./mvnw -q -pl api dependency:go-offline
COPY api/src api/src
RUN ./mvnw -q -pl api package -DskipTests && cp api/target/outflow-api-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 outflow && mkdir /data && chown outflow /data
USER outflow
# Local state outside the DB (IBAN HMAC key). Mount a volume here and back it up with the database.
ENV OUTFLOW_DATA_DIR=/data
VOLUME /data
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
