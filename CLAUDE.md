# Read Option — Claude Code Instructions

## Project
AI-powered NFL fantasy football draft assistant.
Spring Boot 3.5.14, Spring AI 1.1.6, PostgreSQL 16 + pgvector, Flyway, Maven.

## Package Structure
- Base package `app.readoption`
- Sub-packages by domain `app.readoption.player`, `app.readoption.sleeper`, etc.

## Conventions
- Java 17
- JPA entities classes (not records) with Persistable for manual IDs
- DTOs Java records
- Logging SLF4J with {} placeholders, never System.out.println
- Config externalized to application.properties, secrets via ${ENV_VAR}
- Schema changes Flyway migrations only, Hibernate set to validate
- Constructor injection, final fields, no field injection

## Database
- PostgreSQL on localhost5433 (not 5432, WSL conflict)
- Docker Compose `docker compose up -d`
- Credentials readoptionreadoptionreadoption

## Do NOT
- Use Hibernate ddl-auto for schema changes
- Use field injection (@Autowired on fields)
- Use System.out.println for logging
- Hardcode API keys or secrets
- Use Optional for fields, parameters, or DTOs