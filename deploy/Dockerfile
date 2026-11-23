FROM node:24-bookworm-slim AS frontend
WORKDIR /frontend
COPY front-web-rony/package.json front-web-rony/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY front-web-rony/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-jammy AS backend
WORKDIR /backend
COPY back-temp-rony/.mvn/ .mvn/
COPY back-temp-rony/mvnw back-temp-rony/pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY back-temp-rony/src/ src/
RUN ./mvnw clean compile -DskipTests
COPY --from=frontend /frontend/dist/Web-Angular/browser/ target/classes/static/
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /opt/rony
RUN useradd --system --uid 10001 --create-home rony
COPY --from=backend /backend/target/back-rony-1.0.0-SNAPSHOT.jar app.jar
COPY rony-yolo-isaac/runtime/rony_profile.json rony_profile.json
ENV RONY_RUNTIME_PROFILE=/opt/rony/rony_profile.json
USER 10001
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "app.jar", "--server.address=0.0.0.0", "--rony.web-portal=true", "--rony.execution-location=cloud", "--spring.profiles.active=cloud"]
