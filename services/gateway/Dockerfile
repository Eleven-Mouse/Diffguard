FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/diffguard-*.jar diffguard.jar

EXPOSE 8080 9090

ENTRYPOINT ["java", "-jar", "diffguard.jar"]
CMD ["server"]
