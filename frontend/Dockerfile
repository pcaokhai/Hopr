FROM node:24-slim AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:24-slim
WORKDIR /app
# The standalone output already excludes dev dependencies; only the server bundle,
# the static chunks and public assets are needed at runtime.
COPY --from=build /build/.next/standalone ./
COPY --from=build /build/.next/static ./.next/static
COPY --from=build /build/public ./public
# The server only reads its own bundle, so root ownership is fine; what matters is
# that the process itself is unprivileged, so a container breakout cannot land as host root.
RUN useradd -r -u 1001 appuser
USER appuser
ENV PORT=3000 HOSTNAME=0.0.0.0
EXPOSE 3000
ENTRYPOINT ["node", "server.js"]
