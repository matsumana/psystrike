FROM ubuntu:bionic-20191010 as builder

WORKDIR /tmp
RUN apt update && \
    apt install -y curl && \
    curl -L -O https://download.bell-sw.com/java/13.0.1/bellsoft-jdk13.0.1-linux-amd64.deb && \
    apt install -y -f ./bellsoft-jdk13.0.1-linux-amd64.deb
RUN jlink \
    --compress=2 \
    --add-modules=java.base,jdk.unsupported,java.xml,jdk.management,jdk.management.agent,jdk.jfr \
    --output=jre

# --------------------------------
FROM ubuntu:bionic-20191010

COPY --from=builder /tmp/jre /root/jre
ADD ./build/libs/*.jar /root/app.jar
ADD ./docker-entrypoint.sh /root/docker-entrypoint.sh

ENV PATH "/root/jre/bin:$PATH"

CMD ["/root/docker-entrypoint.sh", "java", "-jar", "/root/app.jar"]

EXPOSE 8080
