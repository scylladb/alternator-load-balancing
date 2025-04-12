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
check: check-autopep8 check-ruff

.PHONY: fix
fix: fix-autopep8 fix-ruff

.PHONY: .prepare
.prepare:
	@echo "======== Check and install missing dependencies"
	@pip install -r ./requirement-test.txt

.PHONY: check-autopep8
check-autopep8: .prepare
	@echo "======== Running autopep8 check"
	@autopep8 -r --diff ./

.PHONY: check-ruff
check-ruff: .prepare
	@echo "======== Running ruff check"
	@ruff check

.PHONY: fix-autopep8
fix-autopep8: .prepare
	@echo "======== Running autopep8 fix"
	@autopep8 -r -j 4 -i ./

.PHONY: fix-ruff
fix-ruff: .prepare
	@echo "======== Running ruff fix"
	@ruff check --fix --preview

.PHONY: test
test: unit-test integration-test

.PHONY: unit-test
unit-test:
	@echo "======== Running unit tests"
	@pytest ./test_unit*

.PHONY: integration-test
integration-test: scylla-up
	@echo "======== Running integration tests"
	@pytest ./test_integration*

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
