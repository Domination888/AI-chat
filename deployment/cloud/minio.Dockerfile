# Official prebuilt images may be unavailable. Build the pinned upstream source.
FROM golang:1.24.2-bookworm AS build
WORKDIR /src
RUN git clone --depth 1 --branch RELEASE.2025-04-22T22-12-26Z https://github.com/minio/minio.git .
RUN CGO_ENABLED=0 go build -trimpath -o /out/minio .

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /out/minio /usr/local/bin/minio
# Keep upstream license and source pointer with the independently built service.
COPY --from=build /src/LICENSE /usr/share/doc/minio/LICENSE
ENV MINIO_BROWSER=off
EXPOSE 9000
ENTRYPOINT ["minio"]
