define dl_tgz
	@if ! $(1) 2>/dev/null 1>&2; then \
		[ -d "$(GOBIN)" ] || mkdir "$(GOBIN)"; \
		if [ ! -f "$(GOBIN)/$(1)" ]; then \
			echo "Downloading $(GOBIN)/$(1)"; \
			curl --progress-bar -L $(2) | tar zxf - --wildcards --strip 1 -C $(GOBIN) '*/$(1)'; \
			chmod +x "$(GOBIN)/$(1)"; \
		fi; \
	fi
endef

define dl_bin
	@if ! $(1) 2>/dev/null 1>&2; then \
		[ -d "$(GOBIN)" ] || mkdir "$(GOBIN)"; \
		if [ ! -f "$(GOBIN)/$(1)" ]; then \
			echo "Downloading $(GOBIN)/$(1)"; \
			curl --progress-bar -L $(2) --output "$(GOBIN)/$(1)"; \
			chmod +x "$(GOBIN)/$(1)"; \
		fi; \
	fi
endef

MAKEFILE_PATH := $(abspath $(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
GOOS := $(shell uname | tr '[:upper:]' '[:lower:]')
GOARCH := $(shell go env GOARCH)
DOCKER_COMPOSE_VERSION := 2.34.0
GOLANGCI_VERSION := 1.64.8

GOLANGCI_DOWNLOAD_URL := "https://github.com/golangci/golangci-lint/releases/download/v$(GOLANGCI_VERSION)/golangci-lint-$(GOLANGCI_VERSION)-$(GOOS)-amd64.tar.gz"

ifeq ($(GOARCH),arm64)
	DOCKER_COMPOSE_DOWNLOAD_URL := "https://github.com/docker/compose/releases/download/v$(DOCKER_COMPOSE_VERSION)/docker-compose-$(GOOS)-aarch64"
else ifeq ($(GOARCH),amd64)
	DOCKER_COMPOSE_DOWNLOAD_URL := "https://github.com/docker/compose/releases/download/v$(DOCKER_COMPOSE_VERSION)/docker-compose-$(GOOS)-x86_64"
else
	@printf 'Unknown architecture "%s"\n', "$(GOARCH)"
	@exit 69
endif


ifndef GOBIN
export GOBIN := $(MAKEFILE_PATH)/bin
endif

export PATH := $(GOBIN):$(PATH)

COMPOSE := docker-compose -f $(MAKEFILE_PATH)/test/docker-compose.yml

.PHONY: clean
clean:
	@echo "Cleaning v1"
	@cd v1 && go clean -r && cd ..
	@echo "Cleaning v2"
	@cd v2 && go clean -r && cd ..

.PHONY: build
build:
	@echo "Building v1"
	@cd v1 && go build ./... && cd ..
	@echo "Building v2"
	@cd v2 && go build ./... && cd ..

.PHONY: clean-caches
clean-caches:
	@go clean -r -cache -testcache -modcache ./...

.PHONY: check
check: check-golangci

.PHONY: fix
fix: fix-golangci

.PHONY: check-golangci
check-golangci: $(GOBIN)/golangci-lint
	@echo "======== Lint code for v1"
	@cd v1 && golangci-lint run --config=../.golangci.yml ./...
	@cd ..
	@echo "======== Lint code for v2"
	@cd v2 && golangci-lint run --config=../.golangci.yml ./...

.PHONY: fix-golangci
fix-golangci: $(GOBIN)/golangci-lint
	@echo "======== Fix code for v1"
	@cd v1 && golangci-lint run --fix --config=../.golangci.yml ./...
	@cd ..
	@echo "======== Fix code for v2"
	@cd v2 && golangci-lint run  --fix --config=../.golangci.yml ./...

.PHONY: test
test: build check unit-test integration-test

.PHONY: unit-test
unit-test:
	@echo "======== Running unit tests for v1"
	@cd v1 && go test -v -cover -race ./...
	@cd ..
	@echo "======== Running unit tests for v2"
	@cd v2 && go test -v -cover -race ./...

.PHONY: integration-test
integration-test: scylla-up
	@echo "======== Running unit tests for v1"
	@cd v1 && go test -v -cover -race -tags integration ./...
	@cd ..
	@echo "======== Running unit tests for v2"
	@cd v2 && go test -v -cover -race -tags integration ./...

.PHONY: .prepare-cert
.prepare-cert:
	@[ -f "test/scylla/db.key" ] || (echo "Prepare certificate" && cd test/scylla/ && openssl req -subj "/C=US/ST=Denial/L=Springfield/O=Dis/CN=www.example.com" -x509 -newkey rsa:4096 -keyout db.key -out db.crt -days 3650 -nodes && chmod 644 db.key)

.PHONY: scylla-up
scylla-up: .prepare-cert $(GOBIN)/docker-compose
	@sudo sysctl -w fs.aio-max-nr=10485760
	$(COMPOSE) up -d

.PHONY: scylla-down
scylla-down: $(GOBIN)/docker-compose
	$(COMPOSE) down

.PHONY: scylla-kill
scylla-kill: $(GOBIN)/docker-compose
	$(COMPOSE) kill

.PHONY: scylla-rm
scylla-rm: $(GOBIN)/docker-compose
	$(COMPOSE) rm -f

$(GOBIN)/golangci-lint: Makefile
	$(call dl_tgz,golangci-lint,$(GOLANGCI_DOWNLOAD_URL))

$(GOBIN)/docker-compose: Makefile
	$(call dl_bin,docker-compose,$(DOCKER_COMPOSE_DOWNLOAD_URL))
