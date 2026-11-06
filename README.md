# OpenSimilarity

Self-hostable, clean-room academic **similarity** checker (fingerprint + optional embeddings). It finds overlapping text with explainable spans; humans decide integrity. Not a Turnitin clone and not an AI-guilt scorer.

## Stack

- Java 17 / Spring Boot
- PostgreSQL + [pgvector](https://github.com/pgvector/pgvector) 0.8.7
- Apache Tika ingest (PDF / DOCX / TXT)
- Winnowing + MinHash fingerprints; optional MiniLM embeddings

## Quick start

```bash
docker run -d --name opensimilarity-db \
  -e POSTGRES_USER=opensim -e POSTGRES_PASSWORD=opensim -e POSTGRES_DB=opensimilarity \
  -p 5432:5432 pgvector/pgvector:pg16
docker exec opensimilarity-db psql -U opensim -d opensimilarity -c 'CREATE EXTENSION IF NOT EXISTS vector;'

mvn -q -DskipTests package
java -jar target/opensimilarity.jar
```

Health: `GET http://localhost:8080/api/health`

## Demo API

```bash
curl -s -X POST localhost:8080/api/collections -H 'Content-Type: application/json' \
  -d '{"name":"CSC demo","department":"CS","indexPolicy":"COURSE_ONLY"}'
# then multipart-upload samples under samples/, consentToIndex=true for corpus docs
# POST /api/documents/{id}/checks
```

Design notes: `research/` and `design/`.

## License

MIT
