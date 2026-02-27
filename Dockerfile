FROM eclipse-temurin:21-jre-jammy
RUN groupadd -r kneobroadcaster && useradd -r -g kneobroadcaster kneobroadcaster
RUN apt-get update && apt-get install -y ffmpeg && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /app/segmented /app/merged /app/controller-uploads /app/external /app/file-uploads /var/log/kneobroadcaster \
    && chown -R kneobroadcaster:kneobroadcaster /app /var/log/kneobroadcaster
WORKDIR /app
COPY target/kneobroadcaster-runner.jar app.jar
RUN chown kneobroadcaster:kneobroadcaster app.jar
USER kneobroadcaster
EXPOSE 8080 38708
ENTRYPOINT ["java", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED", "-jar", "app.jar"]