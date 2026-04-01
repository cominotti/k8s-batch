-- Database-per-service: create additional logical databases beyond MYSQL_DATABASE (k8sbatch).
-- This script runs via docker-entrypoint-initdb.d on MySQL first-start init.
-- The primary k8sbatch database is created by the MYSQL_DATABASE env var.
CREATE DATABASE IF NOT EXISTS k8scrud CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON k8scrud.* TO 'k8sbatch'@'%';
FLUSH PRIVILEGES;
