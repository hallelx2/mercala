FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/mercala-image-gen-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
