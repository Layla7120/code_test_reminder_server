# Use an official Python runtime as a parent image
FROM python:3.11-slim
LABEL authors="layla"
# Set the working directory in the container
WORKDIR /

# Copy the requirements file into the container
COPY requirements.txt .

# Install dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Copy the rest of the application code into the container
COPY . .

# Expose the application port (optional, for documentation purposes)
EXPOSE 8080

# Command to run the application
CMD ["python3", "-m", "flask", "run", "--host=0.0.0.0"]
