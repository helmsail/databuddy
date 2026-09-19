# syntax=docker/dockerfile:1
# DataBuddy 应用镜像:多阶段构建(构建段 Maven+JDK17,运行段 JRE17)
# 运行段内置 docker CLI:Python 沙箱经挂载的 /var/run/docker.sock 调宿主 daemon 创建/执行容器

# ============ 构建段 ============
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 依赖解析单独成层:pom 不变时复用缓存(BuildKit cache mount 持久化 ~/.m2,重复构建不再全量下载)
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

# ============ 运行段 ============
FROM eclipse-temurin:17-jre
# docker CLI(来自官方 cli 镜像的静态二进制;沙箱功能需要)
COPY --from=docker:27-cli /usr/local/bin/docker /usr/local/bin/docker
WORKDIR /app
COPY --from=build /build/target/databuddy-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
