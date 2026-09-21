# ---------------------------------------------------------------- build ----
FROM node:24-alpine AS build
WORKDIR /build

# Lockfile first, same layer-caching reason as the backend.
# `npm ci` not `npm install`: it installs exactly what the lockfile says and
# fails if package.json disagrees, so a build is reproducible rather than
# picking up whatever a caret range resolves to today.
COPY package.json package-lock.json* ./
RUN npm ci

COPY . .
RUN npm run build

# -------------------------------------------------------------- runtime ----
# The output is static files. Nothing needs Node at runtime, so it is not here.
FROM nginx:1.27-alpine AS runtime

# openssl is here to GENERATE a development certificate, never to hold one.
# No certificate and no private key is copied into this image: an image layer
# is public to anyone who can pull it, is cached on every machine that ever
# built from it, and cannot be un-published. Certificates arrive as a mount.
RUN apk add --no-cache openssl

COPY --from=build /build/dist /usr/share/nginx/html

# The server blocks are staged here and the entrypoint picks one, because
# which one is right depends on TLS_MODE, which is not known at build time.
COPY nginx/server-http.conf nginx/server-tls.conf /etc/nginx/reconpilot/
COPY nginx/app.conf /etc/nginx/snippets/app.conf
COPY docker-entrypoint.d/20-reconpilot-tls.sh /docker-entrypoint.d/

EXPOSE 80 443
