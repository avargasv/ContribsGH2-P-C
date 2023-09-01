FROM sbtscala/scala-sbt:graalvm-ce-22.3.0-b2-java17_1.9.1_3.3.0 AS builder
WORKDIR /app
ADD . .
RUN sbt compile && sbt package && sbt assembly

FROM openjdk:jre-alpine
COPY --from=builder /app/target/scala-2.13/ContribsGH2-P-C-assembly-0.1.jar contribsgh2-p-c.jar
ENTRYPOINT ["java","-jar","./contribsgh2-p-c.jar"]
