FROM ubuntu:latest
RUN apt-get update && apt-get install -y \
    python3 \
    git \
    curl \
    golang \
    default-jdk \
    zip
