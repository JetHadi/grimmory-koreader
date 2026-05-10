# Simple Docker build/push commands for Grimmory
# Usage examples:
#   make build
#   make push
#   make build-push TAG=latest REGISTRY=registry.lan IMAGE_NAME=grimmory/grimmory

REGISTRY ?= registry.lan
IMAGE_NAME ?= grimmory/grimmory
TAG ?= latest
PLATFORM ?= linux/amd64

IMAGE := $(REGISTRY)/$(IMAGE_NAME):$(TAG)

.PHONY: help build push build-push print-image

help:
	@echo "Targets:"
	@echo "  make build       Build $(IMAGE) for $(PLATFORM) and load locally"
	@echo "  make push        Push existing local image $(IMAGE)"
	@echo "  make build-push  Build and push $(IMAGE) for $(PLATFORM)"
	@echo ""
	@echo "Variables (override with VAR=value):"
	@echo "  REGISTRY=$(REGISTRY)"
	@echo "  IMAGE_NAME=$(IMAGE_NAME)"
	@echo "  TAG=$(TAG)"
	@echo "  PLATFORM=$(PLATFORM)"

print-image:
	@echo $(IMAGE)

build:
	docker buildx build --platform $(PLATFORM) -t $(IMAGE) --load .

push:
	docker push $(IMAGE)

build-push:
	docker buildx build --platform $(PLATFORM) -t $(IMAGE) --push .
