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
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
