-- V016: Migrate seed skills from skill_definition → skill, then drop orphaned legacy tables
-- ============================================================================
-- Background: skill_definition (V012) and skill_embedding (V012) were created as
-- part of the Phase E "Skill Hub" design, but no Java PO/DAO/Service/Controller
-- was ever mapped to them — the legacy SkillRegistry / SkillEmbeddingService are
-- purely in-memory. The V015 `skill` table is the live, DB-backed skill system
-- used by the multi-agent "/" command palette (SkillCatalogService / SkillExecutor
-- / SkillController). This migration moves the 3 built-in seed skills into the
-- live table as public system skills, then drops the dormant legacy tables.

-- 1. Migrate the built-in seed skills from skill_definition into `skill` as
--    public/system skills (user_id = 0 denotes the system/public owner, matching
--    the PUBLIC_SCOPE convention in ToolRegistry). Idempotent: names already
--    present in `skill` are skipped (LEFT JOIN ... IS NULL) so re-running won't
--    create duplicates.
INSERT INTO skill (user_id, name, description, prompt_template, bind_tools, visibility, status, create_time, update_time)
SELECT 0, sd.name, sd.description, sd.prompt_template, sd.required_tools, 'public', 1, sd.create_time, sd.update_time
FROM skill_definition sd
LEFT JOIN skill s ON sd.name = s.name
WHERE s.id IS NULL;

-- 2. Drop the orphaned skill_embedding table. No Java code maps to it; the
--    legacy SkillEmbeddingService stores embeddings in an in-memory Map.
DROP TABLE IF EXISTS skill_embedding;

-- 3. Drop the orphaned skill_definition table. No Java code maps to it; the
--    legacy SkillRegistry is in-memory. Seed data has been migrated above.
DROP TABLE IF EXISTS skill_definition;
