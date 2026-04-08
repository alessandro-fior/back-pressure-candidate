FROM eclipse-temurin:21-jdk-jammy

ARG TARGETARCH

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    NODE_VERSION=20.19.0 \
    PULUMI_VERSION=3.228.0 \
    PULUMI_SKIP_UPDATE_CHECK=true

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        git \
        unzip; \
    rm -rf /var/lib/apt/lists/*; \
    build_arch="${TARGETARCH:-}"; \
    if [ -z "$build_arch" ]; then \
        build_arch="$(dpkg --print-architecture)"; \
    fi; \
    case "$build_arch" in \
        amd64|x86_64) node_arch='x64'; pulumi_arch='x64' ;; \
        arm64|aarch64) node_arch='arm64'; pulumi_arch='arm64' ;; \
        *) echo "Unsupported architecture: $build_arch" >&2; exit 1 ;; \
    esac; \
    workdir='/usr/local/src/dev-toolchain'; \
    mkdir -p "$workdir" /usr/local/lib/nodejs; \
    curl -fsSLo "$workdir/node.tar.gz" "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${node_arch}.tar.gz"; \
    tar -xzf "$workdir/node.tar.gz" -C /usr/local/lib/nodejs; \
    ln -sf "/usr/local/lib/nodejs/node-v${NODE_VERSION}-linux-${node_arch}/bin/node" /usr/local/bin/node; \
    ln -sf "/usr/local/lib/nodejs/node-v${NODE_VERSION}-linux-${node_arch}/bin/npm" /usr/local/bin/npm; \
    ln -sf "/usr/local/lib/nodejs/node-v${NODE_VERSION}-linux-${node_arch}/bin/npx" /usr/local/bin/npx; \
    ln -sf "/usr/local/lib/nodejs/node-v${NODE_VERSION}-linux-${node_arch}/bin/corepack" /usr/local/bin/corepack; \
    curl -fsSLo "$workdir/pulumi.tar.gz" "https://get.pulumi.com/releases/sdk/pulumi-v${PULUMI_VERSION}-linux-${pulumi_arch}.tar.gz"; \
    tar -xzf "$workdir/pulumi.tar.gz" -C /usr/local/lib; \
    ln -sf /usr/local/lib/pulumi/pulumi /usr/local/bin/pulumi; \
    node --version | grep -F "v${NODE_VERSION}" >/dev/null; \
    pulumi version | grep -F "v${PULUMI_VERSION}" >/dev/null; \
    rm -rf "$workdir"

WORKDIR /workspace

CMD ["bash"]
