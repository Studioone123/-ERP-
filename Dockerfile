# ---------------------------------------------------------------------------
# 多阶段构建：builder 只负责编译，runtime 镜像里不含源码与 Maven，体积更小。
#
# 不复制 .mvn/ 是有意为之：项目级 maven.config 把本地仓库固定到 D:/maven/repository，
# 那是 Windows 开发机的路径；容器里用默认 Maven Central + /root/.m2 更干净。
# ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -q -DskipTests -Dmaven.repo.local=/root/.m2/repository package

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
COPY --from=builder /build/target/erp-system.jar app.jar

# H2 数据文件挂到卷里，容器重建不丢数据
ENV ERP_DB_PATH=/data/erpdb \
    JAVA_OPTS="-Xms256m -Xmx512m"
VOLUME ["/data"]
EXPOSE 8080

# 命令行参数优先级最高，避免环境变量 SERVER_PORT 之类把端口改掉
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --server.port=8080"]
