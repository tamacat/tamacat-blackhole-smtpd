FROM amazoncorretto:25-alpine3.24

RUN apk update && apk upgrade && apk add --no-cache

COPY target/tamacat-blackhole-smtpd-1.2-jar-with-dependencies.jar /

CMD ["java", "-jar" ,"tamacat-blackhole-smtpd-1.2-jar-with-dependencies.jar"]
