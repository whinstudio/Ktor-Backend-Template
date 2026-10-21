# Build from the repository root: docker build -f deploy/Dockerfile --build-arg APP_VERSION=<tag or sha> .
# No HEALTHCHECK here: the compose file probes /ready.

FROM gradle:8-jdk21@sha256:c71fc6625c18e889654adeb86c99974028bc888505f6e1fd7eccad057174877d AS build
WORKDIR /src
# The wrapper and build scripts first, so the dependency layer is cached until they change.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties detekt.yml ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon --quiet dependencies --configuration runtimeClasspath > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon shadowJar

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
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
