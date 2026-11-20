FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends squid ca-certificates && rm -rf /var/lib/apt/lists/*
COPY deployment/cloud/squid.conf /etc/squid/squid.conf
CMD ["squid","-N","-f","/etc/squid/squid.conf"]
