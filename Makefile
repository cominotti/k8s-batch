SHELL := /usr/bin/env bash
.SHELLFLAGS := -eu -o pipefail -c

.PHONY: help compile test verify package sonar-local

help:
	@echo "Targets:"
	@echo "  compile      Compile all modules"
	@echo "  test         Run unit tests"
	@echo "  verify       Run integration tests (requires Docker)"
	@echo "  package      Build JAR without tests"
	@echo "  sonar-local  Fetch SonarCloud quality gate and unresolved issues via REST API"

compile:
	mvn -B -ntp clean compile

test:
	mvn -B -ntp test

verify:
	mvn -B -ntp -pl k8s-batch-integration-tests verify

package:
	mvn -B -ntp package -DskipTests

sonar-local:
	@./scripts/sonar-local.sh
