# Java 21 실행 환경
FROM eclipse-temurin:21-jre

# 컨테이너 내부 작업 디렉터리
WORKDIR /app
RUN install -d -o 10001 -g 10001 /app/.homecare-cache

# Gradle 빌드 결과물 복사
COPY build/libs/app.jar app.jar

# Spring Boot 포트
EXPOSE 8080

# Spring Boot 실행
ENTRYPOINT ["java", "-jar", "app.jar"]
