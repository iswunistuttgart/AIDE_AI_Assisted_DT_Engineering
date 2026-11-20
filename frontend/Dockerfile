FROM node:22-alpine
WORKDIR /frontend
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY index.html vite.config.js ./
COPY public public
COPY src src
CMD ["npm", "run", "dev"]
