#!/usr/bin/env bash

set -e

PROJECT_VERSION=$(.github/workflows/get-mvn-property.sh project.version)
echo PROJECT_VERSION="$PROJECT_VERSION" >>"$GITHUB_ENV"
echo PROJECT_VERSION="$PROJECT_VERSION"

if [[ "$PROJECT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  mvn --batch-mode build-helper:parse-version versions:set -DnewVersion=\${parsedVersion.majorVersion}.\${parsedVersion.minorVersion}.\${parsedVersion.incrementalVersion}-SNAPSHOT versions:commit
  IS_RELEASE_VERSION='1'
else
  NEW_PROJECT_VERSION=''
  IS_RELEASE_VERSION='0'
fi
echo NEW_PROJECT_VERSION="$NEW_PROJECT_VERSION" >>"$GITHUB_ENV"
echo NEW_PROJECT_VERSION="$NEW_PROJECT_VERSION"
echo IS_RELEASE_VERSION="$IS_RELEASE_VERSION" >>"$GITHUB_ENV"
echo IS_RELEASE_VERSION="$IS_RELEASE_VERSION"
