FROM eclipse-temurin:21-jre

RUN groupadd -r diffguard && useradd -r -g diffguard diffguard

WORKDIR /app

COPY --chown=diffguard:diffguard target/diffguard-*.jar diffguard.jar

EXPOSE 8080 9090

USER diffguard

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD bash -c '</dev/tcp/127.0.0.1/9090' || exit 1

ENTRYPOINT ["java", "-Xmx512m", "-jar", "diffguard.jar", "tool-server", "--port", "9090"]
