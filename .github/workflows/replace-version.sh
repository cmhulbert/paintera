#!/usr/bin/env bash

set -e

MAVEN_OPTS="$MAVEN_OPTS -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn"
mvn versions:set -DnewVersion=${NEW_PROJECT_VERSION} versions:commit
[ "$PROJECT_VERSION" = "${NEW_PROJECT_VERSION}" ]
