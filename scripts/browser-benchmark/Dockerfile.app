FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636
ARG SOURCE_REVISION
LABEL org.opencontainers.image.revision=$SOURCE_REVISION
WORKDIR /opt/gameyfin
COPY --chown=1337:1337 application.jar /opt/gameyfin/application.jar
COPY --chown=1337:1337 h2.jar /opt/gameyfin/h2.jar
USER 1337:1337
ENV RUNTIME_ENV=docker
ENTRYPOINT ["java", "-jar", "/opt/gameyfin/application.jar"]
