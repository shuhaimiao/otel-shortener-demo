# Use an official Node.js runtime as a parent image
FROM node:20-alpine

# Set the working directory in the container
WORKDIR /usr/src/app

# Copy package.json and package-lock.json (if available)
COPY package*.json ./

# Install application dependencies
RUN npm install
# If you are building your code for production
# RUN npm ci --omit=dev

# Bundle app source
COPY . .

# Expose the port the app runs on
EXPOSE 3001

# Define the command to run the application with OpenTelemetry instrumentation
CMD [ "node", "--require", "./otel-instrumentation.js", "server.js" ]
