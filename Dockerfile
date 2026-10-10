FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY *.java .
RUN javac *.java
CMD ["java", "RateLimiterServer"]
