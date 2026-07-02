-- V12__tenant_region.sql
-- Add region column to tenant table

ALTER TABLE tenant ADD COLUMN region VARCHAR(8) NOT NULL DEFAULT 'US';
