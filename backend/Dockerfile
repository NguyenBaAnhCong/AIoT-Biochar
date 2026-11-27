FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
RUN mvn -B -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache font-dejavu \
    && addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/.tv5-reports && chown app:app /app/.tv5-reports
ENV TV5_PDF_FONT=/usr/share/fonts/dejavu/DejaVuSans.ttf
COPY --from=build /workspace/backend/target/aiot-biochar-backend-0.1.0-SNAPSHOT.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
