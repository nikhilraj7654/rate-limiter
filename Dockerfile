FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY *.java .
COPY index.html .
RUN javac *.java
CMD ["java", "RateLimiterServer"]
