-- ============================================================
-- Phase A Migration: Seed Default Workspaces (V002)
-- Description: Auto-creates a default workspace per existing user
--   and assigns existing resources to it for backward compatibility.
-- Run: Once after V001.
-- ============================================================

-- 1. Create a default workspace per existing user who doesn't have one
INSERT INTO workspace (name, description, owner_id, status, create_time, update_time)
SELECT 
    CONCAT(u.username, '''s Workspace') AS name,
    'Default workspace (auto-created during Phase A migration)' AS description,
    u.id AS owner_id,
    1 AS status,
    NOW() AS create_time,
    NOW() AS update_time
FROM user_info u
WHERE NOT EXISTS (
    SELECT 1 FROM workspace w WHERE w.owner_id = u.id AND w.status = 1
);

-- 2. Insert OWNER membership for each workspace owner
INSERT IGNORE INTO workspace_member (workspace_id, user_id, role, create_time, update_time)
SELECT w.id, w.owner_id, 'OWNER', NOW(), NOW()
FROM workspace w
LEFT JOIN workspace_member wm ON wm.workspace_id = w.id AND wm.user_id = w.owner_id
WHERE wm.id IS NULL;

-- 3. Assign existing database connections to their owner's default workspace
UPDATE db_connection_conf dc
INNER JOIN workspace w ON w.owner_id = dc.user_id AND w.status = 1
SET dc.workspace_id = w.id
WHERE dc.workspace_id IS NULL;

-- 4. Assign existing conversations to their owner's default workspace
UPDATE conversation c
INNER JOIN workspace w ON w.owner_id = c.user_id AND w.status = 1
SET c.workspace_id = w.id
WHERE c.workspace_id IS NULL;

-- 5. Assign existing business knowledge to their owner's default workspace
UPDATE business_knowledge bk
INNER JOIN workspace w ON w.owner_id = bk.user_id AND w.status = 1
SET bk.workspace_id = w.id
WHERE bk.workspace_id IS NULL;

-- 6. Assign existing agent executions to their owner's default workspace
UPDATE agent_execution ae
INNER JOIN workspace w ON w.owner_id = ae.user_id AND w.status = 1
SET ae.workspace_id = w.id
WHERE ae.workspace_id IS NULL;

