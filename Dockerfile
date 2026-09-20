# syntax=docker/dockerfile:1

FROM node:22-bookworm-slim AS web
WORKDIR /src
COPY package.json package-lock.json ./
COPY apps/web/package.json apps/web/
RUN npm ci --no-audit --no-fund
COPY apps/web apps/web
COPY scripts/project-version.mjs scripts/compress-web.mjs scripts/
RUN npm run build:web

FROM golang:1.25.14-bookworm AS server
WORKDIR /build
COPY apps/server/go.mod apps/server/go.sum ./
RUN go mod download
COPY apps/server ./
COPY package.json /version-package.json
RUN VERSION=$(sed -nE 's/^[[:space:]]*"version":[[:space:]]*"([^"]+)".*/\1/p' /version-package.json | head -n1) && \
    CGO_ENABLED=0 go build -trimpath -ldflags="-s -w -X melora/internal/version.Value=${VERSION:-dev}" -o /out/melora ./cmd/melora

FROM alpine:3.22
LABEL org.opencontainers.image.source="https://github.com/Raving4934/Melora" \
      org.opencontainers.image.description="Melora self-hosted music web application"
RUN apk add --no-cache ca-certificates tzdata && adduser -D -u 10001 melora
COPY --from=server /out/melora /usr/local/bin/melora
COPY --from=web /src/apps/web/dist /app/web
RUN mkdir -p /data && chown -R melora:melora /app /data
USER melora
WORKDIR /app
# 容器内是明文 HTTP，TLS 由外层反向代理终止；cloud 模式不提供任何下载能力。
ENV MELORA_ADDR=0.0.0.0:3780 \
    WEB_DIR=/app/web \
    MELORA_DATA_DIR=/data \
    MELORA_DEPLOY_MODE=cloud \
    MELORA_ALLOW_INSECURE_HTTP=1
EXPOSE 3780
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:3780/api/v1/health || exit 1
ENTRYPOINT ["melora"]
