# Build the browser client.
FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Build the Java server and include the PostgreSQL JDBC driver.
FROM gradle:8.14.3-jdk21-alpine AS backend-build
WORKDIR /app
COPY build.gradle settings.gradle ./
RUN gradle --no-daemon dependencies
COPY src/ ./src/
COPY --from=frontend-build /app/src/main/resources/web/ ./src/main/resources/web/
RUN gradle --no-daemon clean test installDist

# Run only the packaged application, not a compiler or build tool.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=backend-build /app/build/install/iou/ ./

ENV PORT=8080
ENV IOU_REQUIRE_DATABASE=true
EXPOSE 8080

CMD ["/app/bin/iou"]
