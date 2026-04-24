#!/bin/bash 
export UID GID
docker compose -f docker-compose.test-local.yml --env-file .env.test-local up --build
