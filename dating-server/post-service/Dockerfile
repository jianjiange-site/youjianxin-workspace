# ===== build stage =====
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 利用 Docker layer cache:先拉依赖,再拷源码
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

# ===== runtime stage =====
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 全系统统一 UTC(红线 5)
ENV TZ=UTC
RUN apk add --no-cache tzdata && cp /usr/share/zoneinfo/UTC /etc/localtime

COPY --from=builder /build/target/post-service.jar /app/app.jar

# 8080 HTTP(actuator)/ 9090 gRPC
EXPOSE 8080 9090

ENTRYPOINT ["java", "-Duser.timezone=UTC", \
            "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", \
            "-jar", "/app/app.jar"]
