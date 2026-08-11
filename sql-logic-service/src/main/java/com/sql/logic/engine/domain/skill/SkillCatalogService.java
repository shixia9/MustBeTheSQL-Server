package com.sql.logic.engine.domain.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sql.logic.engine.infrastructure.dao.SkillDao;
import com.sql.logic.engine.infrastructure.po.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Catalog CRUD for {@link Skill} entities.
 * <p>
 * Ownership is enforced on {@code update}/{@code delete}: only the skill's
 * owner may mutate it. {@code delete} is a soft delete (status → 0) so archived
 * skills remain recoverable, mirroring {@code MemoryItem} archival semantics.
 * {@code listByUser} returns the caller's own private skills plus all public
 * skills (both filtered to status=1), giving the "/" palette a unified view.
 */
@Service
public class SkillCatalogService {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalogService.class);

    private final SkillDao skillDao;

    public SkillCatalogService(SkillDao skillDao) {
        this.skillDao = skillDao;
    }

    /**
     * Create a new skill for {@code userId}. Defaults visibility to "private"
     * and status to active (1) regardless of the inbound values, so a client
     * cannot accidentally publish or archive on create.
     */
    public Skill create(Long userId, Skill skill) {
        skill.setUserId(userId);
        skill.setStatus(1);
        if (skill.getVisibility() == null || skill.getVisibility().isBlank()) {
            skill.setVisibility("private");
        }
        LocalDateTime now = LocalDateTime.now();
        skill.setCreateTime(now);
        skill.setUpdateTime(now);
        skillDao.insert(skill);
        log.debug("[SkillCatalog] created skill id={} name={} for user={}", skill.getId(), skill.getName(), userId);
        return skill;
    }

    /**
     * Update an existing skill owned by {@code userId}. Verifies ownership
     * (throws if the caller is not the owner) then copies the editable fields
     * onto the persisted entity and flushes via {@code updateById}.
     */
    public Skill update(Long userId, Long id, Skill incoming) {
        Skill existing = loadOwnedOrThrow(userId, id);
        if (incoming.getName() != null) existing.setName(incoming.getName());
        if (incoming.getDescription() != null) existing.setDescription(incoming.getDescription());
        if (incoming.getPromptTemplate() != null) existing.setPromptTemplate(incoming.getPromptTemplate());
        if (incoming.getBindTools() != null) existing.setBindTools(incoming.getBindTools());
        if (incoming.getVisibility() != null) existing.setVisibility(incoming.getVisibility());
        if (incoming.getStatus() != null) existing.setStatus(incoming.getStatus());
        existing.setUpdateTime(LocalDateTime.now());
        skillDao.updateById(existing);
        log.debug("[SkillCatalog] updated skill id={} for user={}", id, userId);
        return existing;
    }

    /**
     * Soft-delete (archive) a skill owned by {@code userId} by flipping status
     * to 0. The row is retained so it can be re-activated; archived skills are
     * excluded from {@code listByUser} and {@code SkillExecutor} access.
     */
    public void delete(Long userId, Long id) {
        Skill existing = loadOwnedOrThrow(userId, id);
        existing.setStatus(0);
        existing.setUpdateTime(LocalDateTime.now());
        skillDao.updateById(existing);
        log.debug("[SkillCatalog] archived skill id={} for user={}", id, userId);
    }

    /**
     * List all skills visible to {@code userId}: the caller's own private
     * skills plus every public skill, both filtered to status=1 (active).
     */
    public List<Skill> listByUser(Long userId) {
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<Skill>()
                .eq(Skill::getStatus, 1)
                .and(w -> w.eq(Skill::getUserId, userId).or().eq(Skill::getVisibility, "public"));
        return skillDao.selectList(wrapper);
    }

    /**
     * Load a single skill by id (any status). Used by {@link SkillExecutor}
     * which performs its own active/accessible checks.
     */
    public Optional<Skill> getById(Long id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(skillDao.selectById(id));
    }

    /**
     * Find an active skill by name visible to {@code userId} (the caller's own
     * private skill or any public skill). Returns {@code null} if no active
     * skill with that name is accessible. Used by the "/" palette's
     * {@code inject_prompt} path to resolve a {@code /skillName <task>} input
     * submitted through the agentic stream.
     */
    public Skill findByName(Long userId, String name) {
        if (name == null || name.isBlank()) return null;
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<Skill>()
                .eq(Skill::getStatus, 1)
                .eq(Skill::getName, name)
                .and(w -> w.eq(Skill::getUserId, userId).or().eq(Skill::getVisibility, "public"));
        List<Skill> hits = skillDao.selectList(wrapper);
        return hits == null || hits.isEmpty() ? null : hits.get(0);
    }

    private Skill loadOwnedOrThrow(Long userId, Long id) {
        Skill existing = skillDao.selectById(id);
        if (existing == null) {
            throw new IllegalStateException("Skill not found: " + id);
        }
        if (!userId.equals(existing.getUserId())) {
            throw new IllegalStateException("Skill " + id + " does not belong to user " + userId);
        }
        return existing;
    }
}
