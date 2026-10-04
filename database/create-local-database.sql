-- Run once in MySQL Workbench with a local account allowed to create schemas.
CREATE DATABASE IF NOT EXISTS rony_local CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE rony_local;
-- Flyway creates versioned tables at application startup. Do not duplicate its DDL here.
