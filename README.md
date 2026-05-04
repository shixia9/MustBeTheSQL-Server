# SQL Logic Engine

A full-stack application (React + Spring Boot) that acts as a database visualizer and AI-driven SQL generator.

## Modules

### 1. Database Connection Management
Manage connections to multiple databases (MySQL, PostgreSQL, etc.) securely.

### 2. Workspace
A single-page visualization of database objects:
- **Tree Navigation**: Browse schemas, tables, views, columns, and indexes.
- **Multi-Tab Editor**: Open multiple tables for data preview or SQL consoles side-by-side.
- **DDL Export**: Generate CREATE statements for tables and views.
- **Connection Pool**: Uses HikariCP for connection isolation, lifecycle management, and performance.
- **Dialect Abstraction**: An SPI-like MetaData framework to seamlessly add support for various databases. Currently supports MySQL and PostgreSQL.

### 3. AI Chat & History
Generate SQL using AI, run it safely, and view history.
