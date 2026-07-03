FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/mercala-core-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
