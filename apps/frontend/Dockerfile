# syntax=docker/dockerfile:1

# Node 20 to match "engines": { "node": ">=20.x" } in package.json.
FROM node:20-alpine AS deps
WORKDIR /app
# package-lock.json first so `npm ci` caches as its own layer.
COPY package.json package-lock.json* ./
RUN npm ci

# ── Build stage ───────────────────────────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules node_modules
COPY . .

# ⚠️ NEXT_PUBLIC_* values are inlined into the client bundle at BUILD time,
# not read at runtime. So the API URL has to be correct here, and it has to
# be the address the BROWSER can reach — not the Docker-internal one.
#
# The browser runs on the host machine, outside the Docker network. It can't
# resolve `http://backend:8080`; that name only exists between containers.
# It reaches the backend through the port compose publishes to the host,
# i.e. http://localhost:8080. Getting this wrong is the classic Docker
# gotcha: everything builds, the page loads, and every API call fails.
ARG NEXT_PUBLIC_API_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL
RUN npm run build

# ── Runtime stage ─────────────────────────────────────────────────────
FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production

# next.config.mjs doesn't set output:'standalone', so `next start` needs the
# real node_modules at runtime. Kept as-is rather than changing the Next
# config, because that config is also what Vercel builds from — a packaging
# convenience shouldn't alter how production deploys.
COPY --from=build /app/node_modules node_modules
COPY --from=build /app/.next .next
COPY --from=build /app/public public
COPY --from=build /app/package.json package.json
COPY --from=build /app/next.config.mjs next.config.mjs

# node:alpine ships a non-root `node` user; use it.
RUN chown -R node:node /app
USER node

EXPOSE 3000
CMD ["npm", "run", "start"]
