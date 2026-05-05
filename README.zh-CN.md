# 📊 Must Be The SQL

<p align="center">
  <img src="https://img.shields.io/badge/Frontend-React-blue" />
  <img src="https://img.shields.io/badge/Backend-SpringBoot-green" />
  <img src="https://img.shields.io/badge/AI-LLM-orange" />
  <img src="https://img.shields.io/badge/Database-MySQL%20%7C%20PostgreSQL-lightgrey" />
  <img src="https://img.shields.io/badge/License-MIT-purple" />
</p>

<p align="center">
  <b>💡 一个现代化的数据库可视化与 AI 驱动 SQL 生成平台</b>
</p>

<p align="center">
  <a href="./README.md">🇺🇸 English</a> |
  <a href="#快速开始">⚡ 快速开始</a> |
  <a href="https://github.com/shixia9/MustBeTheSQL">客户端</a>
</p>

## 📖 项目简介

**SQL Logic Engine** 是一个基于 **React + Spring Boot** 构建的全栈智能数据库平台，旨在：

* 🔍 可视化数据库结构
* ✍️ 基于 AI（LLM）自动生成 SQL
* ⚡ 安全执行查询语句
* 📊 提升开发者效率

## ✨ 核心功能

### 🔌 数据库连接管理

* 支持多数据库安全连接
* 支持 **MySQL**、**PostgreSQL**
* 基于 **HikariCP** 实现连接隔离与高效管理

### 🧭 工作区（核心 UI）

一个现代化的单页数据库工作空间：

* **树形导航（Tree Navigation）**

  * 浏览 schema、表、字段、索引等结构
* **多标签编辑器（Multi-Tab Editor）**

  * 支持同时打开多个 SQL 控制台或表预览
* **DDL 导出**

  * 自动生成 `CREATE TABLE` / `VIEW` 语句
* **连接池管理（Connection Pooling）**

  * 基于 HikariCP 提供高性能连接管理
* **方言抽象（Dialect Abstraction）**

  * 基于 SPI 的元数据扩展机制
  * 易于扩展支持新的数据库类型

### 🤖 AI SQL 助手

* 自然语言生成 SQL（NL2SQL）
* SQL 查询解释
* 安全执行（防止破坏性操作，如 DELETE/UPDATE 无条件执行）
* 对话历史记录追踪

## ⚡ 快速开始

### 1. 克隆仓库

```bash
git clone https://github.com/shixia9/MustBeTheSQL-Server.git
git clone https://github.com/shixia9/MustBeTheSQL.git
```

### 2. 启动后端服务

```bash
cd MustBeTheSQL-Server
mvn spring-boot:run
```

### 3. 启动前端项目

```bash
cd MustBeTheSQL
npm install
npm run dev
```
