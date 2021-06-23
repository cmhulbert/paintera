#!/usr/bin/env bash

set -e

PROJECT_VERSION=$(.github/workflows/get-mvn-property.sh project.version)
PROJECT_NAME=$(.github/workflows/get-mvn-property.sh project.name)
echo PROJECT_VERSION="$PROJECT_VERSION" >>"$GITHUB_ENV"
echo PROJECT_VERSION="$PROJECT_VERSION"
echo PROJECT_NAME="$PROJECT_NAME" >>"$GITHUB_ENV"
echo PROJECT_NAME="$PROJECT_NAME"

if [[ "$PROJECT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  IS_RELEASE_VERSION='1'
else
  IS_RELEASE_VERSION='0'
fi

echo IS_RELEASE_VERSION="$IS_RELEASE_VERSION" >>"$GITHUB_ENV"
echo IS_RELEASE_VERSION="$IS_RELEASE_VERSION"

BASENAME="$PROJECT_NAME-$PROJECT_VERSION"
ARTIFACT_NAME="$BASENAME.jar"
ARTIFACT_PATH="build/libs/$ARTIFACT_NAME"
POM_NAME="$BASENAME.pom"
POM_PATH=build/publications/maven/pom-default.xml
MODULE_NAME="$BASENAME.module"
MODULE_PATH=build/publications/maven/module.json

echo ARTIFACT_NAME="${ARTIFACT_NAME}" >>"$GITHUB_ENV"
echo ARTIFACT_PATH="${ARTIFACT_PATH}" >>"$GITHUB_ENV"
echo POM_NAME="$POM_NAME" >>"$GITHUB_ENV"
echo POM_PATH="$POM_PATH" >>"$GITHUB_ENV"
echo MODULE_NAME="$MODULE_NAME" >>"$GITHUB_ENV"
echo MODULE_PATH="$MODULE_PATH" >>"$GITHUB_ENV"
