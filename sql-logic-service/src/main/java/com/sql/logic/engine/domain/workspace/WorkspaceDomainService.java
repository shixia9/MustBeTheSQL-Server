package com.sql.logic.engine.domain.workspace;

import com.sql.logic.engine.infrastructure.dao.WorkspaceDao;
import com.sql.logic.engine.infrastructure.dao.WorkspaceMemberDao;
import com.sql.logic.engine.infrastructure.po.Workspace;
import com.sql.logic.engine.infrastructure.po.WorkspaceMember;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WorkspaceDomainService {

    private final WorkspaceDao workspaceDao;
    private final WorkspaceMemberDao workspaceMemberDao;

    public WorkspaceDomainService(WorkspaceDao workspaceDao, WorkspaceMemberDao workspaceMemberDao) {
        this.workspaceDao = workspaceDao;
        this.workspaceMemberDao = workspaceMemberDao;
    }

    @Transactional
    public Workspace createWorkspace(String name, String description, Long ownerId) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Workspace name is required");
        }
        Workspace workspace = new Workspace();
        workspace.setName(name.trim());
        workspace.setDescription(description);
        workspace.setOwnerId(ownerId);
        workspace.setStatus(1);
        workspaceDao.insert(workspace);

        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspace.getId());
        member.setUserId(ownerId);
        member.setRole(WorkspaceRole.OWNER.name());
        workspaceMemberDao.insert(member);

        return workspace;
    }

    public Workspace getWorkspace(Long id) {
        Workspace workspace = workspaceDao.selectById(id);
        if (workspace == null || workspace.getStatus() == 0) {
            throw new IllegalArgumentException("Workspace not found");
        }
        return workspace;
    }

    public List<Workspace> getUserWorkspaces(Long userId) {
        return workspaceDao.findByMemberUserId(userId);
    }

    public void updateWorkspace(Long id, String name, String description, Long actorUserId) {
        Workspace workspace = getWorkspace(id);
        assertManageAccess(id, actorUserId);

        if (name != null && !name.trim().isEmpty()) {
            workspace.setName(name.trim());
        }
        if (description != null) {
            workspace.setDescription(description);
        }
        workspaceDao.updateById(workspace);
    }

    public void deleteWorkspace(Long id, Long actorUserId) {
        Workspace workspace = getWorkspace(id);
        assertOwnerAccess(id, actorUserId);

        workspace.setStatus(0);
        workspaceDao.updateById(workspace);
    }

    public void addMember(Long workspaceId, Long targetUserId, String role, Long actorUserId) {
        getWorkspace(workspaceId); // ensure workspace exists
        assertManageAccess(workspaceId, actorUserId);

        WorkspaceMember existing = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, targetUserId);
        if (existing != null) {
            throw new IllegalArgumentException("User is already a member of this workspace");
        }

        if (role == null || !isValidRole(role)) {
            throw new IllegalArgumentException("Invalid role: " + role);
        }

        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspaceId);
        member.setUserId(targetUserId);
        member.setRole(role);
        workspaceMemberDao.insert(member);
    }

    public void updateMemberRole(Long workspaceId, Long targetUserId, String newRole, Long actorUserId) {
        getWorkspace(workspaceId);
        assertManageAccess(workspaceId, actorUserId);

        if (newRole == null || !isValidRole(newRole)) {
            throw new IllegalArgumentException("Invalid role: " + newRole);
        }

        WorkspaceMember member = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, targetUserId);
        if (member == null) {
            throw new IllegalArgumentException("User is not a member of this workspace");
        }

        // Cannot demote or remove the OWNER
        if (WorkspaceRole.OWNER.name().equals(member.getRole())) {
            throw new IllegalArgumentException("Cannot change the role of the workspace owner");
        }

        member.setRole(newRole);
        workspaceMemberDao.updateById(member);
    }

    public void removeMember(Long workspaceId, Long targetUserId, Long actorUserId) {
        getWorkspace(workspaceId);
        assertManageAccess(workspaceId, actorUserId);

        WorkspaceMember member = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, targetUserId);
        if (member == null) {
            throw new IllegalArgumentException("User is not a member of this workspace");
        }

        // OWNER cannot be removed
        if (WorkspaceRole.OWNER.name().equals(member.getRole())) {
            throw new IllegalArgumentException("Cannot remove the workspace owner");
        }

        workspaceMemberDao.deleteById(member.getId());
    }

    public List<WorkspaceMember> listMembers(Long workspaceId, Long userId) {
        getWorkspace(workspaceId);
        assertMemberAccess(workspaceId, userId);
        return workspaceMemberDao.findByWorkspaceId(workspaceId);
    }

    public WorkspaceRole getMemberRole(Long workspaceId, Long userId) {
        WorkspaceMember member = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, userId);
        if (member == null || member.getRole() == null) {
            return null;
        }
        try {
            return WorkspaceRole.valueOf(member.getRole());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void assertMemberAccess(Long workspaceId, Long userId) {
        WorkspaceMember member = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, userId);
        if (member == null) {
            throw new IllegalArgumentException("Workspace access denied: user is not a member of this workspace");
        }
    }

    public void assertManageAccess(Long workspaceId, Long userId) {
        WorkspaceRole role = getMemberRole(workspaceId, userId);
        if (role == null || !role.canManage()) {
            throw new IllegalArgumentException("Workspace access denied: user does not have manage permission");
        }
    }

    public void assertOwnerAccess(Long workspaceId, Long userId) {
        WorkspaceRole role = getMemberRole(workspaceId, userId);
        if (role != WorkspaceRole.OWNER) {
            throw new IllegalArgumentException("Workspace access denied: only the workspace owner can perform this action");
        }
    }

    private boolean isValidRole(String role) {
        try {
            WorkspaceRole.valueOf(role);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
