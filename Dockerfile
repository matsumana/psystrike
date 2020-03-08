FROM adoptopenjdk:11.0.6_10-jdk-hotspot-bionic as builder

RUN useradd app
USER app

RUN jlink \
    --compress=2 \
    --add-modules=java.base,jdk.unsupported,java.xml,java.desktop,jdk.management,jdk.management.agent,jdk.jfr \
    --output=/tmp/jre \
    --bind-services

ADD ./build/libs/*.jar /tmp/app.jar

WORKDIR /tmp
RUN jar xvf app.jar


# --------------------------------
FROM ubuntu:bionic-20200219

RUN useradd app
RUN mkdir -p /app/log
RUN chown -R app:app /app
USER app

COPY --from=builder /tmp/jre              /app/jre
COPY --from=builder /tmp/BOOT-INF/lib     /app/lib
COPY --from=builder /tmp/META-INF         /app/META-INF
COPY --from=builder /tmp/BOOT-INF/classes /app

ADD ./docker-entrypoint.sh /app/docker-entrypoint.sh

ENV JAVA_HOME "/app/jre"
ENV PATH "$JAVA_HOME/bin:$PATH"

CMD ["/app/docker-entrypoint.sh", \
     "java", \
     \
     "-Djava.rmi.server.hostname=127.0.0.1", \
     "-Dcom.sun.management.jmxremote", \
     "-Dcom.sun.management.jmxremote.rmi.port=8686", \
     "-Dcom.sun.management.jmxremote.port=8686", \
     "-Dcom.sun.management.jmxremote.local.only=false", \
     "-Dcom.sun.management.jmxremote.ssl=false", \
     "-Dcom.sun.management.jmxremote.authenticate=false", \
     \
     "-Xlog:gc*=debug,safepoint=debug:/app/log/gc_%t_%p.log:time,uptime,level,tags:filesize=1024m,filecount=5", \
     "-Xlog:class+unload=debug:/app/log/class_unload_%t_%p.log:time,uptime,level,tags:filesize=1024m,filecount=5", \
     "-XX:ErrorFile=/app/log/hs_err_pid%p.log", \
     \
     "-XX:StartFlightRecording=name=on_startup,filename=/app/log/flight_recording.jfr,dumponexit=true,delay=2m,maxsize=1024m", \
     \
     "-XX:+ExitOnOutOfMemoryError", \
     \
     "-XX:+HeapDumpOnOutOfMemoryError", \
     "-XX:HeapDumpPath=/app/log", \
     \
     "-XX:MaxDirectMemorySize=16m", \
     "-XX:MaxMetaspaceSize=256m", \
     "-XX:ReservedCodeCacheSize=240m", \
     "-Xss1m", \
     "-Xms400m", \
     "-Xmx400m", \
     \
     "-XX:+UseG1GC", \
     \
     "-cp", \
     "/app:/app/lib/*", \
     "info.matsumana.psystrike.Application"]

EXPOSE 8080
