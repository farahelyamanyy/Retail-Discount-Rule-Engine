# 🛒 Retail Discount Rule Engine

A high-performance, functional rule-based engine built in **Scala** that evaluates retail order transactions against a configurable set of business discount rules, computes final prices, and persists results to an Oracle database — processing up to **10 million orders per batch** with parallel execution and chunked streaming.

---

## 📑 Table of Contents

- [Overview](#overview)
- [Discount Rules](#discount-rules)
- [Project Structure](#project-structure)
- [Data Models](#data-models)
- [Tech Stack](#tech-stack)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Database Setup](#database-setup)
  - [Configuration](#configuration)
  - [Running the Engine](#running-the-engine)
- [How It Works](#how-it-works)
- [Scaling](#scaling)
- [Logging](#logging)
- [Functional Programming Principles](#functional-programming-principles)

---

## Overview

The Retail Discount Rule Engine is a backend data pipeline designed to automate the discount qualification and pricing process for a large-scale retail business. Given a batch of raw order transactions in CSV format, the engine:

1. **Parses** each transaction into a structured data model
2. **Qualifies** each order against a set of configurable business rules
3. **Calculates** the applicable discount and final price
4. **Persists** the processed results into an Oracle database table

The engine is built entirely on **functional programming principles** — no mutable state, no loops, no side effects outside of explicitly managed I/O boundaries. Every transformation in the pipeline is a pure function, making the system predictable, testable, and easy to reason about.

**Key behaviors:**
- Orders that qualify for **no** discount receive **0%**.
- Orders that qualify for **more than one** discount receive the **average of the top 2** discounts.
- All I/O operations (file reading, database access) use functional error handling via Scala's `Try`.
- Engineered to scale from thousands to **10 million+ orders** per batch without running out of memory or blocking on I/O.

---

## Discount Rules

The engine evaluates each order against the following rules. If more than one rule qualifies, the top 2 discounts are averaged and applied.

| # | Qualifying Rule | Calculation |
|---|----------------|-------------|
| 1 | Product expires in **less than 30 days** from transaction date | `(30 - daysRemaining)%` — e.g. 29 days → 1%, 1 day → 29% |
| 2 | Product is **Cheese** or **Wine** | Cheese → 10%, Wine → 5% |
| 3 | Transaction placed on **23rd of March** | 50% |
| 4 | Quantity **≥ 6 units** of the same product | 6–9 → 5%, 10–14 → 7%, 15+ → 10% |
| 5 | Order placed through the **App** | `ceil(quantity / 5) × 5%` — e.g. qty 1–5 → 5%, 6–10 → 10% |
| 6 | Payment made via **Visa** | 5% flat |

> **Rules 5 and 6** were introduced following a business requirement to increase mobile App adoption and promote cashless Visa payments across the customer base.

---

## Project Structure

```
Retail-Discount-Rule-Engine/
├── src/
│   └── main/
│       ├── resources/
│       │   ├── application.conf      # DB connection config (git-ignored)
│       │   └── logback.xml           # Logging configuration
│       └── scala/
│           └── Project/
│               ├── Project.scala     # Case classes + main engine entry point
│               ├── DB.scala          # Database layer (connect, insert, truncate)
│               └── create_table.sql  # DDL for the output table
├── TRX10M.csv                        # Input transactions file (10M orders)(git-ignored)
├── TRX1000.csv                       # Input transactions file (1000 orders)(git-ignored)
├── logs/                             # Generated log output
├── build.sbt                         # SBT build definition
└── .gitignore
```

---

## Data Models

The engine uses **case classes** to represent domain objects, replacing raw opaque tuples with named, self-documenting fields.

```scala
/** Represents a single raw order transaction parsed from the CSV. */
case class Order(
  transactionDate : String,
  productName     : String,
  expiryDate      : String,
  quantity        : Int,
  unitPrice       : Double,
  channel         : String,
  paymentMethod   : String
)

/** Represents a fully processed order enriched with pricing information. */
case class ProcessedOrder(
  order         : Order,
  originalPrice : Double,
  discount      : Double,
  finalPrice    : Double
)
```

Using named fields (`order.productName`, `po.finalPrice`) instead of positional tuple accessors (`order._2`, `result._4`) makes every rule function self-explanatory and eliminates an entire class of off-by-one indexing bugs.

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Scala 2.13.13 |
| Build Tool | SBT |
| Database | Oracle XE (via OJDBC8) |
| Logging | Logback + SLF4J |
| Config | Typesafe Config |
| Parallelism | Scala Parallel Collections + ForkJoinPool |
| Testing | MUnit |

---

## Getting Started

### Prerequisites

- **JDK 8+**
- **SBT** installed ([install guide](https://www.scala-sbt.org/download.html))
- **Oracle Database XE** running locally on port `1521`
- An Oracle user with `CREATE TABLE` and `INSERT` privileges

---

### Database Setup

Connect to your Oracle instance and run the DDL script to create the output table:

```sql
CREATE TABLE orders_processed (
  transaction_date  TIMESTAMP     PRIMARY KEY,
  product_name      VARCHAR2(100),
  expiry_date       DATE,
  quantity          NUMBER,
  unit_price        NUMBER,
  channel           VARCHAR2(50),
  payment_method    VARCHAR2(50),
  original_price    NUMBER,
  discount          NUMBER,
  final_price       NUMBER
);
```

---

### Configuration

Create `src/main/resources/application.conf` with your database credentials:

```hocon
db {
  url      = "jdbc:oracle:thin:@localhost:1521:XE"
  user     = "your_db_user"
  password = "your_db_password"
}
```

> ⚠️ `application.conf` is listed in `.gitignore` and must **never be committed**. Use environment variables or a secrets manager in production environments.

---

### Running the Engine

1. **Clone the repository**
   ```bash
   git clone https://github.com/farahelyamanyy/Retail-Discount-Rule-Engine.git
   cd Retail-Discount-Rule-Engine
   ```

2. **Place the input file** — ensure `TRX10M.csv` is present in the project root directory.

3. **Run with SBT**
   ```bash
   sbt run
   ```

The engine will:
1. Validate the database connection — aborts early if unreachable
2. Truncate `orders_processed` to prepare for a fresh load
3. Open the CSV as a **lazy stream** — no full file load into memory
4. Process each chunk of 50,000 orders **in parallel** across all CPU cores
5. Insert each processed chunk into the database **immediately** after processing
6. Log every pipeline event to `logs/rules_engine.log`

---

## How It Works

```
TRX10M.csv  (10 million orders)
        │
        ▼
Open file as lazy Iterator via Source.fromFile
(lines are read on demand — never all loaded into RAM)
        │
        ▼
Drop CSV header row
        │
        ▼
grouped(50,000) — split into chunks
        │
        ▼
For each chunk:
  ├─ .par  →  ForkJoinPool distributes work across CPU cores
  ├─ splitLines  →  parse CSV line into List[String]
  ├─ getOrder    →  map fields into Order case class
  ├─ evaluate all 6 qualifying rules
  ├─ collect passing discounts → sort descending → take top 2 → average
  ├─ calculateOriginalPrice  (quantity × unitPrice)
  ├─ calculateFinalPrice     (originalPrice − discount%)
  └─ produce ProcessedOrder
        │
        ▼
.toList  →  collect parallel results back into List[ProcessedOrder]
        │
        ▼
DB.insertOrders  →  batch insert chunk into Oracle DB
        │
        ▼
Repeat for next chunk (~200 iterations for 10M orders)
        │
        ▼
File closed automatically by Using — pipeline complete
```

---

## Scaling

The engine was designed and scaled to handle **10 million orders per batch** reliably. Two complementary techniques make this possible.

### 1 — Parallel Processing with `.par` + ForkJoinPool

Each chunk of 50,000 orders is processed using Scala's parallel collections. A dedicated `ForkJoinPool` is configured with 8 threads to distribute the CPU-bound rule evaluation work:

```scala
val parBatch = batch.par
parBatch.tasksupport = new ForkJoinTaskSupport(new ForkJoinPool(8))

val processedBatch = parBatch
  .map(splitLines)
  .map(getOrder)
  .map { order => ... }
  .toList
```

This is safe without any synchronization because every function in the pipeline is **pure** — no shared mutable state, no side effects, no race conditions possible.

| Mode | Behavior |
|------|----------|
| Sequential | 1 core processes all orders one by one |
| Parallel (`.par`) | 8 cores share the work — up to 8× faster |

### 2 — Chunked Streaming via Lazy Iterator

Rather than calling `.toList` on the file source (which forces all 10 million lines into RAM at once), the engine reads the file as a **lazy Iterator** and processes it in chunks of 50,000:

```scala
Using(Source.fromFile(filename)) { source =>
  val lines = source.getLines().buffered
  if (lines.hasNext) lines.next() // Drop header
  lines.grouped(batchSize).zipWithIndex.foreach {
    case (batch, index) =>
      val processedBatch = batch.par.map(...).toList
      DB.insertOrders(conn, processedBatch)   // insert immediately
  }
}
```

This means DB inserts begin within seconds of startup, and memory usage stays flat throughout the entire run regardless of file size.

| Approach | Time to first DB insert |
|--------------------|-------------------------|
| Load all into List | 5+ minutes |
| Chunked streaming | Seconds |

---

## Logging

All engine events are written to `logs/rules_engine.log` in the format:

```
TIMESTAMP  LOG_LEVEL  MESSAGE
```

**Example output for a 10M order run:**
```
2024-03-23 10:00:01  INFO   Engine started
2024-03-23 10:00:01  INFO   [DB] Connection successful
2024-03-23 10:00:02  INFO   [DB] Truncating table...
2024-03-23 10:00:02  INFO   [DB] Table truncated successfully
2024-03-23 10:00:02  INFO   [DB] Starting Processing and Loading total orders
2024-03-23 10:00:04  INFO   [PIPELINE] Processed chunk 1 of 50000 records and Inserting...
2024-03-23 10:00:06  INFO   [PIPELINE] Processed chunk 2 of 50000 records and Inserting...
2024-03-23 10:00:30  INFO   [PROGRESS] Processed and inserted 500000 records...
2024-03-23 10:01:00  INFO   [PROGRESS] Processed and inserted 1000000 records...
...
2024-03-23 10:07:14  INFO   [DB] Data inserted successfully!
2024-03-23 10:07:14  INFO   RULES ENGINE FINISHED SUCCESSFULLY in 432.1 sec
```

---

## Functional Programming Principles

This project strictly adheres to the ITI functional programming constraints:

| Constraint | How it's applied |
|-----------|-----------------|
| Only `val` — no `var` | All bindings are immutable `val`s throughout |
| No mutable data structures | Immutable `List` used exclusively |
| No loops (`for`, `while`) | All iteration via `map`, `filter`, `grouped`, `foreach` |
| Pure functions | Every function's output depends solely on its input with no side effects |
| Functional I/O error handling | All I/O wrapped in `Try` / `Using` |
| Total functions | All functions return a value for every possible input |
| Self-documenting code | Case classes replace opaque tuples (`order.productName` vs `order._2`) |

> **Note on JDBC & ForkJoinPool:** `PreparedStatement.addBatch()` and `ForkJoinPool` are inherently stateful constructs — these are intentional and unavoidable exceptions dictated by the Java ecosystem. Both are isolated to clearly marked sections of the codebase and commented accordingly.

---

## 📄 License

Developed as part of the **ITI Functional Programming with Scala** course — ITI 46.