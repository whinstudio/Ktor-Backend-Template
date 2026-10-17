# Build from the repository root: docker build -f deploy/Dockerfile --build-arg APP_VERSION=<tag or sha> .
# No HEALTHCHECK here: the compose file probes /ready.

FROM gradle:8-jdk21 AS build
WORKDIR /src
# The wrapper and build scripts first, so the dependency layer is cached until they change.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties detekt.yml ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon --quiet dependencies --configuration runtimeClasspath > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon shadowJar

FROM eclipse-temurin:25-jre-alpine
ARG APP_VERSION=dev
ENV APP_VERSION=${APP_VERSION}
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
# A fixed non-root user; the files directory is created here so a fresh named volume inherits its owner.
RUN adduser -u 10001 -D app \
    && mkdir -p /app /data/files \
    && chown 10001 /data/files
COPY --from=build /src/build/libs/app.jar /app/app.jar
USER 10001
EXPOSE 8080
# Logs go to stdout (logback); JAVA_OPTS=-Xmx384m in the .env overrides the default heap share.
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
