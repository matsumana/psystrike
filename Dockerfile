FROM adoptopenjdk:11.0.7_10-jdk-hotspot-bionic as builder

RUN jlink \
    --compress=2 \
    --add-modules=java.base,jdk.unsupported,java.xml,java.desktop,jdk.management,jdk.management.agent,jdk.jfr \
    --output=/tmp/jre \
    --bind-services

# --------------------------------
FROM ubuntu:bionic-20200219

RUN useradd app
RUN mkdir -p /app/log
RUN chown -R app:app /app
USER app

COPY --from=builder --chown=app:app /tmp/jre /app/jre

# the app jar file is unarchived (ref: Makefile)
COPY --chown=app:app ./build/libs/BOOT-INF/lib     /app/lib
COPY --chown=app:app ./build/libs/META-INF         /app/META-INF
COPY --chown=app:app ./build/libs/BOOT-INF/classes /app

COPY --chown=app:app ./docker-entrypoint.sh /app/docker-entrypoint.sh

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
     # Unified JVM Log
     # Since a bunch of logs may be output, it's disabled in this Dockerfile.
     # Please configure this setting in your manifest, if you want to collect them.
     #"-Xlog:gc*=debug,safepoint=debug,class+unload=debug:stdout:time,uptime,level,tags", \
     \
     # Fatal Error Log
     "-XX:ErrorFile=/dev/stderr", \
     \
     # Since JFR data is binary, can't output to stdout
     "-XX:StartFlightRecording=name=on_startup,filename=/app/log/flight_recording.jfr,dumponexit=true,delay=2m,maxsize=1024m", \
     \
     "-XX:+ExitOnOutOfMemoryError", \
     "-XX:+HeapDumpOnOutOfMemoryError", \
     "-XX:HeapDumpPath=/app/log", \
     \
     # JVM memory calculation:
     # Heap Size(Xmx) + MaxMetaSpaceSize + ReservedCodeCacheSize + MaxDirectMemorySize + (Xss * Number of threads)
     #
     # ref:
     # https://github.com/cloudfoundry/java-buildpack-memory-calculator
     # https://github.com/making/blog-handson/blob/master/memory-calculator.md
     # https://bufferings.hatenablog.com/entry/2019/11/18/000007
     #
     # This app has about 20 threads.
     # So the `spec.containers.resources.requests.memory` of this application should be enough for 300Mi.
     "-Xms96m", \
     "-Xmx96m", \
     "-XX:MaxMetaspaceSize=96m", \
     "-XX:ReservedCodeCacheSize=64m", \
     "-XX:MaxDirectMemorySize=16m", \
     "-Xss1m", \
     \
     "-XX:+UseG1GC", \
     \
     "-cp", \
     "/app:/app/lib/*", \
     "info.matsumana.psystrike.Application"]

EXPOSE 8080
