FROM python:3.12-slim-bookworm

WORKDIR /app

RUN groupadd -r groupamaes && useradd -r -g groupamaes groupamaes

# Chromium-only runtime. The sidecar never needs Firefox or WebKit.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 \
        libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 \
        libxrandr2 libgbm1 libasound2 libpango-1.0-0 libcairo2 \
        libx11-6 libx11-xcb1 libxcb1 libxext6 \
        ca-certificates fonts-liberation tini xvfb xauth \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Xvfb runs as the non-root service user and needs to create its Unix socket.
RUN install -d -m 1777 /tmp/.X11-unix

ENV PLAYWRIGHT_BROWSERS_PATH=/app/playwright-browsers \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    XDG_CONFIG_HOME=/tmp/chromium-config

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && playwright install chromium

COPY main.py portfolio_parser.py ./

USER groupamaes
EXPOSE 8001
ENTRYPOINT ["tini", "--"]
CMD ["xvfb-run", "-a", "--server-args=-screen 0 1920x1080x24", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
