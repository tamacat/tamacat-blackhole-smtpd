FROM amazoncorretto:8-alpine3.21

RUN apk update && apk upgrade && apk add --no-cache

COPY target/tamacat-blackhole-smtpd-1.1-jar-with-dependencies.jar /

CMD ["java", "-jar" ,"tamacat-blackhole-smtpd-1.1-jar-with-dependencies.jar"]
