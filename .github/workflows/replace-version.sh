#!/usr/bin/env bash

set -e

mvn --batch-mode versions:set -DnewVersion=${NEW_PROJECT_VERSION} versions:commit
[ "$PROJECT_VERSION" = "${NEW_PROJECT_VERSION}" ]
