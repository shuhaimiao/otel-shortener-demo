# Stage 1: Build the Next.js application
FROM node:20-alpine AS builder

WORKDIR /app

# Copy package.json and package-lock.json (or yarn.lock)
COPY package.json ./
# If you have a package-lock.json or yarn.lock, copy it too
# COPY package-lock.json ./
# COPY yarn.lock ./

# Install dependencies
RUN npm install

# Copy the rest of the application code
COPY . .

# Build the Next.js application
RUN npm run build

# Stage 2: Serve the Next.js application with a production server
FROM node:20-alpine

WORKDIR /app

# Copy the built application from the builder stage
COPY --from=builder /app/.next ./.next
COPY --from=builder /app/public ./public
COPY --from=builder /app/package.json ./package.json
# If you have a next.config.js, copy it too
# COPY --from=builder /app/next.config.js ./next.config.js

# Install production dependencies (Next.js itself)
RUN npm install --omit=dev

EXPOSE 3000

# Command to run the Next.js application
CMD ["npm", "start"]
