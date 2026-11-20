FROM node:24-bookworm-slim AS frontend
WORKDIR /work
COPY apps/desktop/package*.json apps/desktop/
RUN cd apps/desktop && npm ci --ignore-scripts
COPY apps/desktop/ apps/desktop/
RUN cd apps/desktop && npm run build:admin

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /work
COPY apps/server/ apps/server/
COPY packages/rp/ packages/rp/
COPY --from=frontend /work/apps/desktop/dist-admin/ apps/server/src/main/resources/static/admin/
RUN mvn -B -f apps/server/pom.xml clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 story && mkdir /data && chown story /data
COPY --from=build /work/apps/server/target/story-runtime-0.1.0.jar /app/runtime.jar
USER story
ENV SPRING_PROFILES_ACTIVE=cloud STORY_DATA=/data STORY_BIND=0.0.0.0
EXPOSE 8081
ENTRYPOINT ["java","-Xmx768m","-jar","/app/runtime.jar"]
