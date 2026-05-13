FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon -q
COPY src/ src/
RUN ./gradlew assemble --no-daemon -q

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/build/libs/dd-java-agent.jar /app/dd-java-agent.jar
COPY --from=build /build/build/libs/java-csv-datadog-demo-1.0.0.jar /app/app.jar
COPY data/ /app/data/

ENV DD_SERVICE=java-csv-datadog-demo
ENV DD_ENV=dev
ENV DD_VERSION=1.0.0
ENV DD_AGENT_HOST=datadog-agent

ENTRYPOINT ["java", \
  "-javaagent:/app/dd-java-agent.jar", \
  "-Ddd.trace.sample.rate=1", \
  "-Ddd.logs.injection=true", \
  "-jar", "/app/app.jar"]
CMD ["data/sample-input.txt", "data/parsed-output.csv"]
