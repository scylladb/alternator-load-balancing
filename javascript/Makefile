define dl_tgz
	@if ! $(1) 2>/dev/null 1>&2; then \
		[ -d "$(BIN)" ] || mkdir "$(BIN)"; \
		if [ ! -f "$(BIN)/$(1)" ]; then \
			echo "Downloading $(BIN)/$(1)"; \
			curl --progress-bar -L $(2) | tar zxf - --wildcards --strip 1 -C $(BIN) '*/$(1)'; \
			chmod +x "$(BIN)/$(1)"; \
		fi; \
	fi
endef

define dl_bin
	@if ! $(1) 2>/dev/null 1>&2; then \
		[ -d "$(BIN)" ] || mkdir "$(BIN)"; \
		if [ ! -f "$(BIN)/$(1)" ]; then \
			echo "Downloading $(BIN)/$(1)"; \
			curl --progress-bar -L $(2) --output "$(BIN)/$(1)"; \
			chmod +x "$(BIN)/$(1)"; \
		fi; \
	fi
endef

MAKEFILE_PATH := $(abspath $(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
DOCKER_COMPOSE_VERSION := 2.34.0
ARCH := $(shell uname -m)
OS := $(shell uname -s | tr A-Z a-z)

ifndef BIN
export BIN := $(MAKEFILE_PATH)/bin
endif

export PATH := $(BIN):$(PATH)

DOCKER_COMPOSE_DOWNLOAD_URL := "https://github.com/docker/compose/releases/download/v$(DOCKER_COMPOSE_VERSION)/docker-compose-$(OS)-$(ARCH)"

COMPOSE := docker-compose -f $(MAKEFILE_PATH)/docker/docker-compose.yml

.PHONY: check
check: check-eslint

.PHONY: fix
fix: fix-eslint

.PHONY: .prepare
.prepare:
	@echo "======== Installing Node.js dependencies"
	@npm install

.PHONY: check-eslint
check-eslint: .prepare
	@echo "======== Running ESLint check"
	@npx eslint *.js

.PHONY: fix-eslint
fix-eslint: .prepare
	@echo "======== Running ESLint fix"
	@npx eslint *.js --fix

.PHONY: test
test: test-unit test-integration

.PHONY: test-unit
test-unit:
	@echo "======== Running unit tests"
	@npm test

.PHONY: test-integration
test-integration: scylla-up
	@echo "======== Running integration tests"
	@NODE_TLS_REJECT_UNAUTHORIZED=0 npm run test:integration

.PHONY: .prepare-cert
.prepare-cert:
	@[ -f "docker/scylla/db.key" ] || (echo "Prepare certificate" && cd docker/scylla/ && openssl req -subj "/C=US/ST=Denial/L=Springfield/O=Dis/CN=www.example.com" -x509 -newkey rsa:4096 -keyout db.key -out db.crt -days 3650 -nodes)

.PHONY: scylla-up
scylla-up: .prepare-cert $(BIN)/docker-compose
	@sudo sysctl -w fs.aio-max-nr=10485760
	$(COMPOSE) up -d

.PHONY: scylla-down
scylla-down: $(BIN)/docker-compose
	$(COMPOSE) down

.PHONY: scylla-kill
scylla-kill: $(BIN)/docker-compose
	$(COMPOSE) kill

.PHONY: scylla-clean
scylla-clean: $(BIN)/docker-compose scylla-kill
	$(COMPOSE) rm

$(BIN)/docker-compose: Makefile
	$(call dl_bin,docker-compose,$(DOCKER_COMPOSE_DOWNLOAD_URL))