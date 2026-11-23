#!/bin/sh
set -eu
umask 077

ca_file="${RONY_DB_CA_FILE:-/etc/secrets/aiven-ca.pem}"
if [ ! -r "$ca_file" ]; then
    echo 'Missing Aiven CA certificate. Add aiven-ca.pem as a Render Secret File.' >&2
    exit 1
fi

# This store contains a public CA only; changeit is its integrity password,
# not a database password. Credentials are read by Spring from the environment.
trust_dir="$(mktemp -d /tmp/rony-aiven.XXXXXX)"
keytool -importcert -noprompt -alias aiven -file "$ca_file" \
    -keystore "$trust_dir/ca.p12" -storetype PKCS12 \
    -storepass changeit >/dev/null
export RONY_DB_TRUSTSTORE_URL="file:$trust_dir/ca.p12"

exec java -jar /opt/rony/app.jar "$@"
