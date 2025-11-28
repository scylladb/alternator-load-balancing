SHELL := bash
.ONESHELL:
.SHELLFLAGS := -eo pipefail -c

mvn = mvn

ifdef IS_CICD
    mvn = mvn --no-transfer-progress
endif

define dl_bin
	@if ! $(1) 2>/dev/null 1>&2; then
		[ -d "$(BIN)" ] || mkdir "$(BIN)";
		if [ ! -f "$(BIN)/$(1)" ]; then
			echo "Downloading $(BIN)/$(1)";
			curl --progress-bar -L $(2) --output "$(BIN)/$(1)";
			chmod +x "$(BIN)/$(1)";
		fi;
	fi
endef

MAKEFILE_PATH := $(abspath $(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
BIN := $(MAKEFILE_PATH)/bin
OS := $(shell uname | tr '[:upper:]' '[:lower:]')
ARCH := $(shell uname -m)
DOCKER_COMPOSE_VERSION := 2.34.0

ifeq ($(ARCH),aarch64)
	DOCKER_COMPOSE_DOWNLOAD_URL := "https://github.com/docker/compose/releases/download/v$(DOCKER_COMPOSE_VERSION)/docker-compose-$(OS)-aarch64"
else ifeq ($(ARCH),x86_64)
	DOCKER_COMPOSE_DOWNLOAD_URL := "https://github.com/docker/compose/releases/download/v$(DOCKER_COMPOSE_VERSION)/docker-compose-$(OS)-x86_64"
else
	@printf 'Unknown architecture "%s"\n', "$(GOARCH)"
	@exit 69
endif

COMPOSE = bin/docker-compose -f $(MAKEFILE_PATH)/test/docker-compose.yml

clean:
	$mvn clean

verify:
	$mvn verify
	$mvn javadoc:test-javadoc javadoc:test-aggregate javadoc:test-aggregate-jar javadoc:test-jar javadoc:test-resource-bundle
	$mvn javadoc:jar javadoc:aggregate javadoc:aggregate-jar javadoc:resource-bundle

fix:
	$mvn com.coveo:fmt-maven-plugin:format
	echo y | ${mvn} javadoc:fix
 	echo y | ${mvn} javadoc:test-fix

compile:
	$mvn compile

compile-test:
	$mvn test-compile

.prepare-environment-update-aio-max-nr:
	@if (( $$(< /proc/sys/fs/aio-max-nr) < 2097152 )); then
		echo 2097152 | sudo tee /proc/sys/fs/aio-max-nr >/dev/null
	fi

.prepare-docker-compose: .prepare-bin
	@if [[ -f "$(BIN)/docker-compose" ]] && "$(BIN)/docker-compose" --version 2>/dev/null | grep "$(DOCKER_COMPOSE_VERSION)" >/dev/null; then
		echo "docker-compose $(DOCKER_COMPOSE_VERSION) is already installed"
	else
		echo "Downloading $(BIN)/docker-compose";
		curl --progress-bar -L $(DOCKER_COMPOSE_DOWNLOAD_URL) --output "$(BIN)/docker-compose";
		chmod +x "$(BIN)/docker-compose";
	fi

.prepare-bin:
	@[ -d "$(BIN)" ] || mkdir "$(BIN)";

.PHONY: .prepare-cert
.prepare-cert:
	@[ -f "${MAKEFILE_PATH}/test/scylla/db.key" ] || (echo "Prepare certificate" && cd ${MAKEFILE_PATH}/test/scylla/ && openssl req -subj "/C=US/ST=Denial/L=Springfield/O=Dis/CN=www.example.com" -x509 -newkey rsa:4096 -keyout db.key -out db.crt -days 3650 -nodes && chmod 644 db.key)

.PHONY: scylla-start
scylla-start: .prepare-cert .prepare-docker-compose .prepare-environment-update-aio-max-nr
	$(COMPOSE) up -d

.PHONY: scylla-stop
scylla-stop: .prepare-docker-compose
	$(COMPOSE) down

.PHONY: scylla-kill
scylla-kill: .prepare-docker-compose
	$(COMPOSE) kill

.PHONY: scylla-rm
scylla-rm: .prepare-docker-compose
	$(COMPOSE) rm -f
