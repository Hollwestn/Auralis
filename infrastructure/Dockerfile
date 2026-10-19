FROM eclipse-temurin:25-jre-noble
WORKDIR /app
RUN groupadd --gid 10001 auralis && useradd --uid 10001 --gid 10001 --no-create-home auralis && mkdir -p /app/data/music && chown -R 10001:10001 /app
COPY backend/server/target/auralis-server-0.1.0-SNAPSHOT.jar /app/server.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/server.jar"]
