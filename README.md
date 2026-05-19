# Read Option

AI-powered NFL fantasy football draft and team management assistant. Helps users make better decisions during drafts and in-season management by aggregating player projections, supporting custom tactics, and providing real-time recommendations powered by LLM reasoning over real data.

## Status

**Phase 1 — Data Foundation** (in progress)

## Stack

- Java 17
- Spring Boot 3.5.14
- Spring AI 1.1.6 (Anthropic Claude)
- PostgreSQL 16 + pgvector (Docker)
- Spring Data JPA + Hibernate
- Flyway (schema migration)
- Sleeper API (NFL data source)
- Maven (via Maven Wrapper)

## Required Environment Variables

- `ANTHROPIC_API_KEY` — Get one at https://console.anthropic.com

## Running

```bash
# Start the database
docker compose up -d

# Run the application
./mvnw spring-boot:run
```

## Project Structure

```
read-option/
├── src/main/java/app/readoption/   ← application code
├── src/main/resources/
│   ├── application.properties       ← configuration
│   └── db/migration/                ← Flyway SQL migrations
├── docker-compose.yml               ← PostgreSQL + pgvector
└── pom.xml                          ← Maven build file
```

## Roadmap

- [x] Phase 0 — LLM fundamentals, Spring AI basics (see playground repos)
- [ ] Phase 1 — Data foundation (ETL into PostgreSQL, player data pipeline)
- [ ] Phase 2 — Projections aggregator (multi-source data, LLM-assisted reconciliation)
- [ ] Phase 3 — User customization (natural language tactics → structured rules)
- [ ] Phase 4 — AI draft assistant (agent with tool calling, real-time recommendations)
- [ ] Phase 5 — In-season management (weekly updates, lineup decisions)

## License

MIT
