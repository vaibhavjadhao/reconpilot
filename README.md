# ReconPilot

A reconciliation platform that independently recomputes what a merchant
*should* have been charged, compares it against what they *were* charged, and
drives the resulting discrepancies to recovery.

One engine, two adapters:

- **MDR Guard** -- verifies UPI MDR against the NPCI framework effective
  15 October 2026.
- **SettleSure** -- verifies marketplace settlements (Amazon, Flipkart, Meesho)
  against published fee schedules.

## Stack

Java 21 - Spring Boot - PostgreSQL - Redis - Kafka - React + TypeScript

## Documentation

- [Architecture decision records](docs/adr/)
- [Open questions](docs/OPEN-QUESTIONS.md)
