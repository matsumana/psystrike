FROM ubuntu:bionic-20191010 as builder

RUN apt update && \
    apt install -y curl && \
    curl -L -O https://download.bell-sw.com/java/13.0.1/bellsoft-jdk13.0.1-linux-amd64.deb && \
    apt install -y -f ./bellsoft-jdk13.0.1-linux-amd64.deb

RUN useradd app
USER app

WORKDIR /tmp
RUN jlink \
    --compress=2 \
    --add-modules=java.base,jdk.unsupported,java.xml,java.desktop,jdk.management,jdk.management.agent,jdk.jfr \
    --output=jre \
    --bind-services

ADD ./build/libs/*.jar /tmp/app.jar
RUN jar xvf *.jar


# --------------------------------
FROM ubuntu:bionic-20191010

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
     "-Xlog:gc*=debug:/app/log/gc_%t_%p.log:time,uptime,level,tags:filesize=1024m,filecount=5", \
     "-Xlog:safepoint=debug:/app/log/safepoint_%t_%p.log:time,uptime,level,tags:filesize=1024m,filecount=5", \
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
