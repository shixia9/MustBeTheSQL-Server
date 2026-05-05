# 📊 Must Be The SQL

<p align="center">
  <img src="https://img.shields.io/badge/Frontend-React-blue" />
  <img src="https://img.shields.io/badge/Backend-SpringBoot-green" />
  <img src="https://img.shields.io/badge/AI-LLM-orange" />
  <img src="https://img.shields.io/badge/Database-MySQL%20%7C%20PostgreSQL-lightgrey" />
  <img src="https://img.shields.io/badge/License-MIT-purple" />
</p>

<p align="center">
  <b>💡 A modern database visualization and AI-powered SQL generation platform</b>
</p>

<p align="center">
  <a href="./README.zh-CN.md">🇨🇳 中文文档</a> |
  <a href="#quick-start">⚡ Quick Start</a> |
  <a href="https://github.com/shixia9/MustBeTheSQL">Client</a>
</p>


## 📖 Introduction

**SQL Logic Engine** is a full-stack intelligent database platform built with **React + Spring Boot**, designed to:

- 🔍 Visualize database structures
- ✍️ Generate SQL via AI (LLM-powered)
- ⚡ Execute queries safely
- 📊 Improve developer productivity


## ✨ Features

### 🔌 Database Connection Management

- Secure multi-database connection support
- Supports **MySQL**, **PostgreSQL**
- Connection isolation via **HikariCP**


### 🧭 Workspace (Core UI)

A modern single-page database workspace:

- **Tree Navigation**
  - Browse schemas, tables, columns, indexes
- &#x20;**Multi-Tab Editor**
  - Open multiple SQL consoles or table previews
- **DDL Export**
  - Auto-generate `CREATE TABLE` / `VIEW` statements
- **Connection Pooling**
  - High-performance management via HikariCP
- **Dialect Abstraction**
  - SPI-style metadata extension
  - Easily extend to new databases


### 🤖 AI SQL Assistant

- Natural language → SQL generation
- Query explanation
- Safe execution (prevent destructive queries)
- Chat history tracking


## ⚡ Quick Start

### 1. Clone the repository

```bash
git clone https://github.com/shixia9/MustBeTheSQL-Server.git
git clone https://github.com/shixia9/MustBeTheSQL.git
```

### 2. Start Backend

```bash
cd MustBeTheSQL-Server
mvn spring-boot:run
```

### 3. Start Frontend

```bash
cd MustBeTheSQL
npm install
npm run dev
```

