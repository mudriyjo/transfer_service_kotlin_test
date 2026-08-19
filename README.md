# Transfer Service — Lead Backend Take-home

This repository contains a small banking service that supports:

- transfers between accounts in the bank;
- transfers to an external beneficiary through a core-banking integration;
- transfers scheduled for later execution;
- querying transfers owned by the authenticated customer.

The project is also used as a four-hour, AI-enabled backend engineering exercise. The assignment is described at the end of this README.

## Prerequisites

For the Docker workflow:

- Docker Engine 20.10 or newer;
- Docker Compose v2;
- `curl` for the examples.

For running the service directly:

- JDK 21;
- Docker is required by the integration tests;
- no system Gradle installation is required.

## Quick start with Docker Compose

Build and start the application, PostgreSQL, and Kafka:

```bash
docker compose config
docker compose build
docker compose up -d
```

Wait until the application becomes ready:

```bash
until curl --fail --silent http://localhost:8080/actuator/health/readiness; do
  sleep 2
done
```

Check the running containers:

```bash
docker compose ps
```

The local profile creates the following demo data:

| Value | Identifier |
|---|---|
| Customer | `00000000-0000-0000-0000-000000000001` |
| Source account | `10000000-0000-0000-0000-000000000001` |
| Internal destination | `10000000-0000-0000-0000-000000000002` |

Local requests authenticate with the `X-Customer-Id` header.

## Basic operations

### Internal transfer

```bash
curl --fail-with-body \
  -X POST http://localhost:8080/api/v1/transfers/internal \
  -H 'Content-Type: application/json' \
  -H 'X-Customer-Id: 00000000-0000-0000-0000-000000000001' \
  -H 'Idempotency-Key: readme-internal-0001' \
  -d '{
    "sourceAccountId": "10000000-0000-0000-0000-000000000001",
    "destinationAccountId": "10000000-0000-0000-0000-000000000002",
    "amount": 25.00,
    "currency": "EUR"
  }'
```

Repeat the same request with the same `Idempotency-Key` to receive the original transfer.

### External transfer

```bash
curl --fail-with-body \
  -X POST http://localhost:8080/api/v1/transfers/external \
  -H 'Content-Type: application/json' \
  -H 'X-Customer-Id: 00000000-0000-0000-0000-000000000001' \
  -H 'Idempotency-Key: readme-external-0001' \
  -d '{
    "sourceAccountId": "10000000-0000-0000-0000-000000000001",
    "beneficiaryAccount": "DE89370400440532013000",
    "amount": 15.00,
    "currency": "EUR"
  }'
```

### Scheduled transfer

The timestamp must be in the future and within the scheduling horizon. The first
command below works with GNU `date` (Linux) and BSD `date` (macOS).

```bash
execute_at="$(
  date -u -d '+5 minutes' '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null ||
  date -u -v+5M '+%Y-%m-%dT%H:%M:%SZ'
)"

curl --fail-with-body \
  -X POST http://localhost:8080/api/v1/transfers/scheduled \
  -H 'Content-Type: application/json' \
  -H 'X-Customer-Id: 00000000-0000-0000-0000-000000000001' \
  -d "{
    \"sourceAccountId\": \"10000000-0000-0000-0000-000000000001\",
    \"beneficiaryAccount\": \"DE89370400440532013000\",
    \"amount\": 10.00,
    \"currency\": \"EUR\",
    \"scheduleId\": \"readme-scheduled-0001\",
    \"executeAt\": \"${execute_at}\"
  }"
```

### Query transfers

Replace `TRANSFER_ID` with the identifier returned by a command:

```bash
curl --fail-with-body \
  -H 'X-Customer-Id: 00000000-0000-0000-0000-000000000001' \
  http://localhost:8080/api/v1/transfers/TRANSFER_ID
```

List the current customer's transfers:

```bash
curl --fail-with-body \
  -H 'X-Customer-Id: 00000000-0000-0000-0000-000000000001' \
  'http://localhost:8080/api/v1/transfers?limit=20'
```

Optional list filters are `status` and `type`.

## Running with Gradle

Start only the infrastructure:

```bash
docker compose up -d postgres kafka
```

Run the application with the local profile:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

Build the executable jar:

```bash
./gradlew clean bootJar
java -jar build/libs/transfer-service.jar --spring.profiles.active=local
```

## Profiles

| Profile | Intended use | Required services |
|---|---|---|
| `local` | Local development and README examples | PostgreSQL and Kafka |
| `test` | Automated tests | Test-managed PostgreSQL and CBS stub |
| `production` | Deployed service | PostgreSQL, Kafka, CBS endpoint, and JWT issuer |

Do not run the service without selecting an appropriate profile. Production settings are supplied through environment variables; see `application-production.yml` for their names.

## Tests

Fast unit tests do not require Docker:

```bash
./gradlew test
```

Run the PostgreSQL integration suite:

```bash
./gradlew integrationTest
```

Run the external contract suite:

```bash
./gradlew contractTest
```

Run every verification task:

```bash
./gradlew check
```

## Docker image

Build the image independently of Compose:

```bash
docker build -t transfer-service:local .
docker image inspect transfer-service:local --format '{{.Config.User}}'
```

The inspection command should print `transfer`. The application image exposes port `8080`.

## Stopping and resetting the service

Stop the containers while keeping the PostgreSQL volume:

```bash
docker compose down
```

Remove containers and local database data:

```bash
docker compose down -v
```

The second command permanently removes the local Compose volume.

## Troubleshooting

View application logs:

```bash
docker compose logs -f app
```

If port `5432`, `8080`, or `9092` is already in use, stop the conflicting service or update the corresponding Compose port mapping.

If integration tests cannot start PostgreSQL, verify that the Docker daemon is running:

```bash
docker info
```

If migrations fail after switching branches, reset only the local Compose data and start again:

```bash
docker compose down -v
docker compose up -d --build
```

## Assignment

### Scenario

You joined a team responsible for this transfer service. The service runs in multiple application instances and handles financial operations through both local storage and external dependencies. The team has received intermittent reliability and support reports, but no single root cause has been confirmed.

You may use AI coding tools during the exercise. You remain responsible for every conclusion and code change.

### Timebox

Spend no more than four hours after the initial `./gradlew test` preflight succeeds. Stop when the timebox expires and document unfinished work or setup problems in the pull request.

### Your task

1. Build a working model of the relevant behavior.
2. Identify at least two material production risks.
3. Rank those risks using impact, likelihood, and evidence from the repository.
4. Select one risk and implement the smallest safe end-to-end fix.
5. Add deterministic regression coverage for the scenario you fixed.
6. Run the relevant verification commands.
7. Submit the code as a focused pull or merge request.

You are not expected to review or improve every file. Fixing cosmetic debt without addressing a material production risk does not satisfy the task.

### Constraints

- Preserve existing public API compatibility unless your PR explicitly justifies a migration path.
- Do not rewrite an applied Flyway migration; add a new migration when the schema must change.
- Keep unrelated refactoring out of the change.
- Do not disable existing tests or weaken their assertions.
- Do not add timing-dependent tests.
- Assume the production service runs in multiple pods.

### Pull request description

Include the following sections:

```text
## Execution path reviewed

## Risks found and prioritization

## Selected change

## Tests and commands run

## Compatibility, rollout, and rollback

## AI tools and verification

## Residual risks and analysis boundaries
```

For AI-assisted work, name the tools used, explain how you verified important claims, and describe at least one suggestion that you rejected or changed after checking it.

### Evaluation

The review considers:

- Kotlin/JVM and concurrency reasoning;
- ability to navigate an unfamiliar service and isolate a useful vertical slice;
- distributed-system, data, and transaction reasoning;
- testing, security, and production readiness;
- responsible use and independent verification of AI-generated conclusions.

The quality and evidence behind prioritization matter more than the number of files changed.
