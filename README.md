# QueryVault

QueryVault is a simplified analytics dashboard for Supabase PostgreSQL built with plain Java 17, the built-in `HttpServer`, JDBC, and a single vanilla HTML frontend.

## Project Structure

```text
queryvault/
├── backend/
│   ├── QueryVaultServer.java
│   ├── config.txt
│   └── postgresql.jar
└── frontend/
    └── index.html
```

## Requirements

- Java 17+
- PostgreSQL JDBC Driver saved as `backend/postgresql.jar`
- Supabase PostgreSQL connection string and credentials
- A browser with internet access for Google Fonts and Chart.js CDN

## Configure

Edit `backend/config.txt`:

```text
DB_URL=jdbc:postgresql://db.xxxx.supabase.co:5432/postgres
DB_USER=postgres
DB_PASSWORD=yourpassword
PORT=8080
```

For Supabase, the JDBC URL usually points at your database host on port `5432`.

## Run Backend

From `queryvault/backend`:

Windows:

```powershell
javac -cp postgresql.jar QueryVaultServer.java
java -cp ".;postgresql.jar" QueryVaultServer
```

Linux/Mac:

```bash
javac -cp postgresql.jar QueryVaultServer.java
java -cp ".:postgresql.jar" QueryVaultServer
```

## Run Frontend

Open `queryvault/frontend/index.html` directly in a browser.

The default backend URL is:

```text
http://localhost:8080
```

## API

- `GET /api/health`
- `POST /api/query`
- `GET /api/tables`
- `GET /api/tables/{table}/columns`
- `GET /api/tables/{table}/preview`
- `GET /api/tables/{table}/count`
- `GET /api/stats`

## Notes

- CORS headers are included on every response.
- Table routes validate identifiers before building dynamic SQL.
- Custom SQL execution uses plain JDBC and returns dynamic JSON based on `ResultSetMetaData`.
- The SQL runner is powerful by design. Use a read-only database user if you want to prevent writes from the dashboard.
