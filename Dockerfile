# Use an official Python runtime as a parent image
FROM python:3.11-slim
LABEL authors="layla"

# Set the working directory in the container
WORKDIR /app

# Copy the requirements file into the container
COPY requirements.txt .

# Install dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Copy the rest of the application code into the container
COPY . .

# Set the PORT environment variable (Cloud Run uses 8080 by default)
ENV PORT 8080

# Expose port 8080 for Cloud Run
EXPOSE 8080

# Command to run the application using Gunicorn
CMD ["gunicorn", "-b", "0.0.0.0:8080", "run:app"]
