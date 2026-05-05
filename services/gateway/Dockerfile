FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y git curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY target/diffguard-*.jar diffguard.jar

EXPOSE 8080 9090

HEALTHCHECK --interval=10s --timeout=3s --retries=2 --start-period=5s \
  CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "diffguard.jar"]
CMD ["server", "--config", "/project/application.yml"]
