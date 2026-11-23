FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY src/ src/
RUN ./mvnw --batch-mode --no-transfer-progress clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /opt/rony
RUN if ! getent group 1000 >/dev/null; then groupadd --gid 1000 rony; fi \
    && useradd --system --uid 10001 --gid 1000 --create-home rony
COPY --from=build /build/target/back-rony-1.0.0-SNAPSHOT.jar app.jar
COPY deploy/rony_profile.json rony_profile.json
COPY --chmod=755 deploy/render-start.sh /opt/rony/start.sh
ENV SPRING_PROFILES_ACTIVE=render
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0 -XX:InitialRAMPercentage=15.0"
USER 10001:1000
EXPOSE 10000
ENTRYPOINT ["/opt/rony/start.sh"]
