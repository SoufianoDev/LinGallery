# syntax=docker/dockerfile:1
FROM rust:1.85-slim AS rust-stage

FROM eclipse-temurin:21-jdk AS lingallery-dev

RUN \
    --mount=type=cache,target=/var/cache/apt,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    git \
    gcc \
    libc6-dev \
    make \
    pkg-config \
    libfuse3-dev \
    libusb-1.0-0-dev \
    && rm -rf /var/lib/apt/lists/*

COPY --from=rust-stage /usr/local/cargo /usr/local/cargo
COPY --from=rust-stage /usr/local/rustup /usr/local/rustup

RUN useradd -m -u 1000 lingallery && \
    chmod -R a+w /usr/local/cargo /usr/local/rustup

ENV CARGO_HOME=/usr/local/cargo \
    RUSTUP_HOME=/usr/local/rustup \
    PATH=/usr/local/cargo/bin:$PATH

USER lingallery
WORKDIR /workspace

CMD ["/bin/bash"]
