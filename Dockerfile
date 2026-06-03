FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy

# Устанавливаем ВСЕ инструменты с поддержкой HEIC
RUN apt-get update && apt-get install -y --no-install-recommends \
    libheif1 \
    libheif-examples \
    ffmpeg \
    libavcodec-extra \
    libvips-tools \
    imagemagick \
    libmagickcore-6.q16-6-extra \
    && rm -rf /var/lib/apt/lists/* \
    && apt-get clean

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Директория для временных файлов
RUN mkdir -p /tmp/heic-convert && chmod 777 /tmp/heic-convert

ENV PORT=8080
ENV JAVA_OPTS="-Xmx512m -XX:+UseContainerSupport"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]