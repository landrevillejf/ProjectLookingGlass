# syntax=docker/dockerfile:1
#
# lg3d-webserver container image.
#
# The build context MUST be the repository root (docker-compose.yml sets
# `context: ..`) because lg3d-webserver is one module of a multi-project Gradle
# build. To build by hand, run from the repo root:
#   docker build -f lg3d-webserver/Dockerfile -t lg3d-webserver .

# ---- Stage 1: build the executable bootJar with the project's own toolchain ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /repo
# Copy the whole repo (a repo-root .dockerignore keeps VCS/build outputs out) and
# build just this module's bootJar; the Gradle wrapper pins the toolchain.
COPY . .
RUN ./gradlew --no-daemon :lg3d-webserver:bootJar

# ---- Stage 2: run it on a slim JRE with the docker CLI to supervise apps ----
FROM eclipse-temurin:21-jre AS runtime
# The server deploys and supervises containerized apps by shelling out to the
# `docker` CLI, so bake the client in; compose mounts the host daemon socket
# (/var/run/docker.sock) so the CLI can reach it. No daemon -> the server still
# boots and reports dockerAvailable=false.
COPY --from=docker:27-cli /usr/local/bin/docker /usr/local/bin/docker
WORKDIR /app
# The bootJar is written under the repo's build-gradle output dir with the `app`
# classifier (see lg3d-webserver/build.gradle).
COPY --from=build /repo/lg3d-webserver/build-gradle/libs/lg3d-webserver-*-app.jar app.jar
EXPOSE 8080
# Deployment state (deployments.json) lives under ~/.lg3d/webserver; mount a volume
# there to keep it across restarts. Override the port with SERVER_PORT and any
# webserver.* key with its relaxed-binding env form (e.g. WEBSERVER_PUBLIC_HOST).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
