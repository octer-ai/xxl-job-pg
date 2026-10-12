# base image
FROM eclipse-temurin:17-jre

# maintainer
MAINTAINER xuxueli

# set params
ENV PARAMS=""

# set timezone
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# copy jar
COPY target/xxl-job-executor-sample-springboot.jar /app.jar

# command
# log home: -e LOG_HOME=/data/applogs
# jvm options: -e JAVA_OPTS="-Xms128m -Xmx128m"
# app params: -e PARAMS="--server.port=8080"
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS ${LOG_HOME:+-DLOG_HOME=$LOG_HOME} -jar /app.jar $PARAMS"]
