# Database Setup Guide

This guide explains how to set up and work with PostgreSQL for the GPT Translation Provider.

## Quick Start

1. **Start PostgreSQL with Docker Compose:**
   ```bash
   docker compose up -d postgres
   
   # OR with Podman
   podman compose up -d postgres
   ```

2. **Verify database is running:**
   ```bash
   docker compose ps
   # OR
   podman compose ps
   ```

3. **Run the application:**
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```

## Database Configuration

### Environment Variables (Production)
For production deployments, the application uses these environment variables:
- `DATABASE_URL`: Full database connection URL
- `DATABASE_USERNAME`: Database user
- `DATABASE_PASSWORD`: Database password

### Local Development Configuration
For local development, values are hardcoded in `application-local.yml`:
- **Host:** localhost
- **Port:** 5432
- **Database:** your_gpt_translation
- **Username:** your_user
- **Password:** your_password

## Flyway Database Migrations

### Migration Files Location
- Directory: `src/main/resources/db/migration/`
- Naming Convention: `V{version}__{description}.sql`
- Example: `V1__Initial_schema.sql`

### Managing Migrations

1. **Create a new migration:**
   ```bash
   # Create file: src/main/resources/db/migration/V2__Add_new_table.sql
   ```

2. **Check migration status:**
   ```bash
   mvn flyway:info
   ```

3. **Apply migrations manually:**
   ```bash
   mvn flyway:migrate
   ```

### Current Schema
The initial migration (`V1__Initial_schema.sql`) creates:
- `translation_requests` table for storing translation data
- Indexes for performance optimization
- Automatic `updated_at` timestamp trigger

## Database Management

### Connect to PostgreSQL
```bash
# Using Docker Compose
docker compose exec postgres psql -U your_user -d your_gpt_translation

# Using Podman Compose
podman compose exec postgres psql -U your_user -d your_gpt_translation

# Using local psql client
psql -h localhost -p 5432 -U your_user -d your_gpt_translation
```

### Common Commands
```sql
-- List all tables
\dt

-- Describe table structure
\d translation_requests

-- Check Flyway migration history
SELECT * FROM flyway_schema_history;

-- View translation requests
SELECT * FROM translation_requests;
```

### Reset Database
```bash
# Stop and remove containers with volumes
docker compose down -v
# OR
podman compose down -v

# Start fresh
docker compose up -d postgres
# OR
podman compose up -d postgres
```

## Troubleshooting

### Common Issues

1. **Connection refused:**
   - Ensure PostgreSQL is running: `docker compose ps` or `podman compose ps`
   - Check port availability: `lsof -i :5432`

2. **Flyway migration errors:**
   - Check migration file syntax
   - Verify migration order (versions)
   - Review logs: `docker compose logs postgres` or `podman compose logs postgres`

3. **Permission errors:**
   - Verify database credentials match between `docker-compose.yml` and `application-local.yml`
   - Check PostgreSQL logs for authentication issues

4. **Container runtime issues:**
   - If Docker isn't available, use Podman commands instead
   - For Podman without compose: Use the manual `podman run` command shown in Quick Start

### Health Check
The PostgreSQL container includes a health check that verifies database availability.
Check status: `docker compose ps postgres` or `podman compose ps postgres`

## Configuration Details

The application uses a two-tier configuration approach:

- **`application.yml`**: Uses environment variables with fallback defaults
- **`application-local.yml`**: Overrides with hardcoded local development values

This ensures:
- Production uses secure environment variables
- Local development "just works" without additional setup
