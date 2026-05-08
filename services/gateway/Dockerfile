FROM eclipse-temurin:21-jre

RUN groupadd -r diffguard && useradd -r -g diffguard diffguard

WORKDIR /app

COPY --chown=diffguard:diffguard target/diffguard-*.jar diffguard.jar

EXPOSE 8080 9090

USER diffguard

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-Xmx512m", "-jar", "diffguard.jar"]
