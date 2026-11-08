# Coolify "Build Pack: Dockerfile" fallback (build context = repo root).
# Primary path is Nixpacks via root package.json — this is plan B.
FROM node:20-alpine

WORKDIR /app
ENV PORT=8080 \
    DATA_DIR=/app/data

# Install deps first (better layer caching)
COPY backend/package.json backend/package-lock.json ./
RUN npm ci --omit=dev || npm install --omit=dev

# App code
COPY backend/server.js ./
COPY backend/src ./src
COPY backend/public ./public

EXPOSE 8080
VOLUME /app/data
CMD ["node", "server.js"]
