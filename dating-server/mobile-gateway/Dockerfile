# syntax=docker/dockerfile:1
# dating-server mobile-gateway 多阶段镜像
# BuildKit cache mount 复用 Maven 依赖，mvn package 在容器内完成，不依赖外部预编译。

# === 第一阶段:构建 ===
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
# Maven mirror: 公共依赖走 aliyun 加速;jianjiange-public/snapshots/releases(私有 jar)继续走 Nexus
RUN mkdir -p /root/.m2 && echo '<settings><mirrors><mirror><id>aliyun</id><url>https://maven.aliyun.com/repository/public</url><mirrorOf>*,!jianjiange-public,!jianjiange-snapshots,!jianjiange-releases</mirrorOf></mirror></mirrors><profiles><profile><id>jianjiange-snapshot-policy</id><activation><activeByDefault>true</activeByDefault></activation><repositories><repository><id>jianjiange-public</id><url>https://nexus.jianjiange.site/repository/maven-public/</url><snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots><releases><enabled>true</enabled></releases></repository></repositories></profile></profiles></settings>' > /root/.m2/settings.xml
COPY pom.xml .
ARG CACHE_ID=maven
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository,id=maven-shared mvn -B -ntp -Dmaven.wagon.http.connectionTimeout=30000 -Dmaven.wagon.http.readTimeout=60000 -Dmaven.wagon.http.retryHandler.count=3 package -DskipTests

# === 第二阶段:运行 ===
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache tzdata curl && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
