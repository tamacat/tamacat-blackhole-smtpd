FROM amazoncorretto:8-alpine3.21
#FROM adoptopenjdk/openjdk8:alpine-jre

RUN apk update && apk upgrade && apk add --no-cache

COPY target/tamacat-blackhole-smtpd-1.0-jar-with-dependencies.jar /

CMD ["java", "-jar" ,"tamacat-blackhole-smtpd-1.0-jar-with-dependencies.jar"]
