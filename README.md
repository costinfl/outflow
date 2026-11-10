# Outflow

Personal finance app answering "where does my money go?". Upload bank statements from several accounts; Outflow
imports every transaction exactly once, classifies it, and finds recurring payments for you to confirm. Everything
stays on your machine: no external APIs, no telemetry.

## Run it locally

Full stack in Docker (needs Docker Desktop or Docker Engine):

```
docker compose up --build
```

Then open http://localhost:3000. The API is on :8080 and Postgres on :5432.

For development, run Postgres in Docker and the rest from source:

```
docker compose up -d postgres
./mvnw -pl api spring-boot:run       # API on :8080
npm --prefix web ci
npm --prefix web run dev             # app on http://localhost:5173
```

More commands (tests, the API contract, the demo build) are in [CLAUDE.md](CLAUDE.md).

## Start over

Removing containers does not remove your data. The database lives in a Docker volume (`pgdata`) and the key that
protects stored IBANs in another (`apidata`). Both outlive every container, so a new Postgres container picks the old
data up again.

To delete everything and start empty, from the repository folder:

```
docker compose down -v
```

`-v` removes the volumes along with the containers. In Docker Desktop you can do the same by hand: delete the
containers, then, under **Volumes**, delete `<folder>_pgdata` and `<folder>_apidata` (`<folder>` is the name of the
repository folder, e.g. `outflow_pgdata`).

If you run the API from source, its IBAN key is a file instead: `api/data/iban-hmac.key` when started with
`./mvnw -pl api spring-boot:run` (Maven runs it from the `api` folder), or `data/iban-hmac.key` when started from the
repository folder (e.g. from an IDE). Neither is committed. Delete that folder too. `OUTFLOW_DATA_DIR` moves it.

Delete the database and the IBAN key together, or keep both: a database kept with a new key no longer recognises
the IBANs it stored, so own-account transfers stop being found by IBAN.
