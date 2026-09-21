-- V6 created the least-privilege role with a literal password:
--
--     CREATE ROLE reconpilot_app LOGIN PASSWORD 'localdev_app';
--
-- which made the credential unconfigurable. A deployment could set
-- DB_APP_PASSWORD to anything and the role would still only accept the value
-- compiled into the migration, so the application could never authenticate.
-- Found by actually deploying; nothing in the test suite could have caught it,
-- because Testcontainers connects as the owner.
--
-- V6 cannot be edited: Flyway checksums applied migrations, and changing one
-- breaks validation on every database that already ran it. So this sets the
-- password instead, from a placeholder supplied by the environment.

ALTER ROLE reconpilot_app WITH PASSWORD '${appPassword}';

-- NOTE ON ROTATION
--
-- This runs once. Flyway checksums the file, not the substituted value, so
-- changing DB_APP_PASSWORD later will NOT re-run it -- the application would
-- then fail to authenticate exactly as it did here.
--
-- Rotating the password is an operational task, not a migration:
--
--     ALTER ROLE reconpilot_app WITH PASSWORD '<new>';   -- then redeploy
--
-- Managing a credential through migrations at all is a compromise. It keeps
-- the deployment to one command, at the cost of the password appearing in a
-- statement the database may log. A deployment with a secrets manager should
-- set the role's password there and leave this migration inert.
