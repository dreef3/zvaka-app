FROM eclipse-temurin:17-jdk-jammy

SHELL ["/bin/bash", "-c"]
ARG DEBIAN_FRONTEND=noninteractive

# Android SDK command-line tools
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates git unzip gnupg software-properties-common \
    python3 python3-pip python3-venv python3-yaml jq \
    && add-apt-repository -y ppa:deadsnakes/ppa \
    && apt-get update && apt-get install -y --no-install-recommends \
    python3.12 python3.12-venv \
    && rm -rf /var/lib/apt/lists/*

# Install Android SDK command-line tools
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
      -o /tmp/cmdtools.zip && \
    unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools && \
    mv /tmp/cmdtools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm -rf /tmp/cmdtools.zip /tmp/cmdtools

# Accept Android SDK licenses and install platform + build tools
RUN yes | sdkmanager --licenses && \
    sdkmanager "platforms;android-35" "build-tools;35.0.1" "platform-tools"

# Node (for Claude Code npm install)
RUN curl -fsSL https://deb.nodesource.com/setup_lts.x | bash - && \
    apt-get install -y nodejs && rm -rf /var/lib/apt/lists/*

# Claude Code + OpenCode CLIs
RUN npm install -g @anthropic-ai/claude-code opencode-ai

# Non-root user (Claude Code requires non-root for --dangerously-skip-permissions)
RUN useradd -m -s /bin/bash -u 1000 dev \
    && mkdir -p /workspace \
    && chown dev:dev /workspace \
    && chown -R dev:dev /opt/android-sdk

USER dev
WORKDIR /workspace
