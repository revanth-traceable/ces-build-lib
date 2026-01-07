FROM eclipse-temurin:11.0.25_9-jre-alpine

LABEL maintainer="ces-build-lib"
LABEL description="Jenkins Pipeline Shared Library"

WORKDIR /app

COPY target/*.jar /app/ces-build-lib.jar

RUN addgroup -g 1000 appuser && \
    adduser -D -u 1000 -G appuser appuser && \
    chown -R appuser:appuser /app

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "ces-build-lib.jar"]
