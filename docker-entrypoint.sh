#!/bin/sh

set -x

# add Kubernetes cert to Java key store
echo changeit | keytool -import -trustcacerts -file /var/run/secrets/kubernetes.io/serviceaccount/ca.crt -keystore $JAVA_HOME/lib/security/cacerts -noprompt

# export token as environment variable
export INFO_MATSUMANA_KUBERNETES_TOKEN=$(cat /run/secrets/kubernetes.io/serviceaccount/token)

exec "$@"
