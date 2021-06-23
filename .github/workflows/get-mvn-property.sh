#!/usr/bin/env bash

set -e

[ -n "$1" ]

mvn -q -Denforcer.skip=true -Dexec.executable=echo -Dexec.args="\${$1}" --non-recursive validate exec:exec 2>&1 | head -n1
