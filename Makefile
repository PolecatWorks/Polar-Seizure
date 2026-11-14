.PHONY: all build test clean \
        build-be build-fe build-android build-tools \
        test-be test-fe test-android test-tools \
        ps-be-dev ps-be-test ps-be-check ps-be-fmt ps-be-docker ps-be-docker-run \
        ps-fe-dev ps-fe-test ps-fe-docker ps-fe-docker-run \
        ps-android-build ps-android-test ps-android-lint \
        ps-tools-build ps-tools-test \
        pre-commit-run pre-commit-install

BASE_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

# Container & Application Configuration
BE_IMAGE := polar-seizure-backend
FE_IMAGE := polar-seizure-frontend

BE_PORT := 8080
BE_HEALTH_PORT := 8079
FE_PORT := 4200

BE_INTERNAL_PORT := 8080
BE_INTERNAL_HEALTH_PORT := 8079
FE_INTERNAL_PORT := 80

# Auto-detect Android SDK and compatible Java (AGP 8.7 requires Java 17–21)
export ANDROID_HOME ?= $(firstword $(wildcard $(HOME)/Library/Android/sdk /opt/homebrew/share/android-commandlinetools /usr/local/share/android-sdk))
ANDROID_JAVA_HOME := $(firstword $(wildcard \
  /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
))
ifeq ($(strip $(ANDROID_JAVA_HOME)),)
  ANDROID_JAVA_HOME := $(shell /usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null)
endif
ANDROID_GRADLE_ENV := $(if $(ANDROID_JAVA_HOME),JAVA_HOME=$(ANDROID_JAVA_HOME))

# Default target
all: build test

# Top-level compound targets
build: build-be build-fe build-android build-tools
test: test-be test-fe test-android test-tools

# --- Backend Container Targets ---
build-be: ps-be-docker
test-be: ps-be-test

ps-be-test:
	cd backend-container && cargo test -- --test-threads=1

ps-be-check:
	cd backend-container && cargo check

ps-be-fmt:
	cd backend-container && cargo fmt --check

ps-be-dev:
	-@lsof -t -i :$(BE_PORT) | xargs kill -9 2>/dev/null || true
	-@lsof -t -i :$(BE_HEALTH_PORT) | xargs kill -9 2>/dev/null || true
	cd backend-container && \
	RUST_LOG=debug \
	SP_BE__DEBUGGING__ENABLE_DEV_AUTH=true \
	cargo run -- --config-path config/default.yaml --secrets-dir config/ serve

ps-be-docker:
	docker build -t $(BE_IMAGE):latest backend-container

ps-be-docker-run: ps-be-docker
	docker run -it --rm --name $(BE_IMAGE) \
		-p $(BE_PORT):$(BE_INTERNAL_PORT) \
		-p $(BE_HEALTH_PORT):$(BE_INTERNAL_HEALTH_PORT) \
		$(BE_IMAGE):latest

# --- Frontend Container Targets ---
build-fe: ps-fe-docker
test-fe: ps-fe-test

frontend-container/node_modules: frontend-container/package.json
	cd frontend-container && npm install

ps-fe-dev: frontend-container/node_modules
	-@lsof -t -i :$(FE_PORT) | xargs kill -9 2>/dev/null || true
	cd frontend-container && npm start

ps-fe-test: frontend-container/node_modules
	cd frontend-container && npm test -- --watch=false --browsers=ChromeHeadless

ps-fe-docker:
	docker build -t $(FE_IMAGE):latest frontend-container

ps-fe-docker-run: ps-fe-docker
	docker run -it --rm --name $(FE_IMAGE) \
		-p $(FE_PORT):$(FE_INTERNAL_PORT) \
		$(FE_IMAGE):latest

# --- Android App Targets ---
build-android: ps-android-build
test-android: ps-android-test

ps-android-build:
	cd android-app && $(ANDROID_GRADLE_ENV) ./gradlew assembleDebug

ps-android-test:
	cd android-app && $(ANDROID_GRADLE_ENV) ./gradlew testDebugUnitTest

ps-android-lint:
	cd android-app && $(ANDROID_GRADLE_ENV) ./gradlew lintDebug

# --- Telemetry Analyzer Tool Targets ---
build-tools: ps-tools-build
test-tools: ps-tools-test

ps-tools-build:
	cd telemetry-analyzer && cargo build --release

ps-tools-test:
	cd telemetry-analyzer && cargo test

# --- Quality Assurance & Pre-commit Hooks ---
pre-commit-install:
	git config --local core.hooksPath ""
	pre-commit install

pre-commit-run:
	pre-commit run --all-files

clean:
	rm -rf android-app/app/build android-app/.gradle
	cd backend-container && cargo clean
	rm -rf frontend-container/dist
