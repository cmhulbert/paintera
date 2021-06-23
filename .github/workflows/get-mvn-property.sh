#!/usr/bin/env bash

set -e

[ -n "$1" ]
MAVEN_OPTS="$MAVEN_OPTS -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn"
mvn -q -Denforcer.skip=true -Dexec.executable=echo -Dexec.args="\${$1}" --non-recursive validate exec:exec 2>&1
