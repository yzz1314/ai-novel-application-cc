# 使用多阶段构建
ARG DOCKER_REGISTRY=docker.m.daocloud.io/library
FROM ${DOCKER_REGISTRY}/maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# 复制pom.xml
COPY pom.xml .

# 下载依赖
RUN mvn dependency:go-offline -B

# 复制源代码
COPY src ./src

# 编译打包
RUN mvn clean package -DskipTests

# 运行阶段
FROM ${DOCKER_REGISTRY}/eclipse-temurin:17-jre-alpine

WORKDIR /app

# 复制jar文件
COPY --from=builder /app/target/*.jar app.jar

# 暴露端口
EXPOSE 8080

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]
