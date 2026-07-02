package com.sql.logic.engine.application.service;

import com.sql.logic.engine.common.dto.AddMemberRequest;
import com.sql.logic.engine.common.dto.CreateInvitationRequest;
import com.sql.logic.engine.common.dto.CreateWorkspaceRequest;
import com.sql.logic.engine.common.dto.InvitationDTO;
import com.sql.logic.engine.common.dto.UpdateMemberRoleRequest;
import com.sql.logic.engine.common.dto.WorkspaceDTO;
import com.sql.logic.engine.common.dto.WorkspaceMemberDTO;
import com.sql.logic.engine.domain.workspace.WorkspaceDomainService;
import com.sql.logic.engine.domain.workspace.WorkspaceRole;
import com.sql.logic.engine.infrastructure.dao.UserInfoDao;
import com.sql.logic.engine.infrastructure.dao.WorkspaceDao;
import com.sql.logic.engine.infrastructure.po.UserInfo;
import com.sql.logic.engine.infrastructure.po.Workspace;
import com.sql.logic.engine.infrastructure.po.WorkspaceInvitation;
import com.sql.logic.engine.infrastructure.po.WorkspaceMember;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class WorkspaceManagementAppService {

    private final WorkspaceDomainService workspaceDomainService;
    private final WorkspaceDao workspaceDao;
    private final UserInfoDao userInfoDao;

    public WorkspaceManagementAppService(WorkspaceDomainService workspaceDomainService, WorkspaceDao workspaceDao, UserInfoDao userInfoDao) {
        this.workspaceDomainService = workspaceDomainService;
        this.workspaceDao = workspaceDao;
        this.userInfoDao = userInfoDao;
    }

    /**
     * List all workspaces the user is a member of, with role info and member count.
     *
     * @param userId current user ID
     * @return list of workspace DTOs
     */
    public List<WorkspaceDTO> listMyWorkspaces(Long userId) {
        List<Workspace> workspaces = workspaceDomainService.getUserWorkspaces(userId);
        if (workspaces == null || workspaces.isEmpty()) {
            return Collections.emptyList();
        }
        return workspaces.stream()
                .map(w -> mapToDTO(w, userId))
                .collect(Collectors.toList());
    }

    /**
     * Create a new workspace.
     *
     * @param req    creation request
     * @param userId current user ID (becomes owner)
     * @return created workspace DTO
     */
    public WorkspaceDTO createWorkspace(CreateWorkspaceRequest req, Long userId) {
        Workspace workspace = workspaceDomainService.createWorkspace(req.getName(), req.getDescription(), userId);
        return mapToDTO(workspace, userId);
    }

    /**
     * Update workspace name and/or description.
     *
     * @param id     workspace ID
     * @param req    update request
     * @param userId current user ID
     * @return updated workspace DTO
     */
    public WorkspaceDTO updateWorkspace(Long id, CreateWorkspaceRequest req, Long userId) {
        workspaceDomainService.updateWorkspace(id, req.getName(), req.getDescription(), userId);
        Workspace workspace = workspaceDomainService.getWorkspace(id);
        return mapToDTO(workspace, userId);
    }

    /**
     * Soft-delete a workspace (owner only).
     *
     * @param id     workspace ID
     * @param userId current user ID
     */
    public void deleteWorkspace(Long id, Long userId) {
        workspaceDomainService.deleteWorkspace(id, userId);
    }

    /**
     * Add a member to a workspace.
     *
     * @param workspaceId  workspace ID
     * @param req          add member request
     * @param actorUserId  current user ID (must have manage access)
     */
    public void addMember(Long workspaceId, AddMemberRequest req, Long actorUserId) {
        workspaceDomainService.addMember(workspaceId, req.getUserId(), req.getRole(), actorUserId);
    }

    /**
     * Update a member's role in a workspace.
     *
     * @param workspaceId  workspace ID
     * @param targetUserId the member whose role is being changed
     * @param req          role update request
     * @param actorUserId  current user ID (must have manage access)
     */
    public void updateMemberRole(Long workspaceId, Long targetUserId, UpdateMemberRoleRequest req, Long actorUserId) {
        workspaceDomainService.updateMemberRole(workspaceId, targetUserId, req.getRole(), actorUserId);
    }

    /**
     * Remove a member from a workspace.
     *
     * @param workspaceId  workspace ID
     * @param targetUserId the member to remove
     * @param actorUserId  current user ID (must have manage access)
     */
    public void removeMember(Long workspaceId, Long targetUserId, Long actorUserId) {
        workspaceDomainService.removeMember(workspaceId, targetUserId, actorUserId);
    }

    /**
     * List all members of a workspace.
     *
     * @param workspaceId workspace ID
     * @param userId      current user ID (must be a member)
     * @return list of member DTOs
     */
    public List<WorkspaceMemberDTO> listMembers(Long workspaceId, Long userId) {
        List<WorkspaceMember> members = workspaceDomainService.listMembers(workspaceId, userId);
        if (members == null || members.isEmpty()) {
            return Collections.emptyList();
        }
        return members.stream()
                .map(this::mapToMemberDTO)
                .collect(Collectors.toList());
    }

    /**
     * Map Workspace entity to WorkspaceDTO, resolving the user's role and member count.
     *
     * @param w      workspace entity
     * @param userId current user ID
     * @return workspace DTO
     */
    private WorkspaceDTO mapToDTO(Workspace w, Long userId) {
        WorkspaceDTO dto = new WorkspaceDTO();
        dto.setId(w.getId());
        dto.setName(w.getName());
        dto.setDescription(w.getDescription());
        dto.setOwnerId(w.getOwnerId());
        dto.setStatus(w.getStatus());
        dto.setCreateTime(w.getCreateTime());

        // Resolve the current user's role in this workspace
        WorkspaceRole role = workspaceDomainService.getMemberRole(w.getId(), userId);
        if (role != null) {
            dto.setRole(role.name());
        }

        // Resolve member count
        List<WorkspaceMember> members = workspaceDomainService.listMembers(w.getId(), userId);
        dto.setMemberCount(members != null ? members.size() : 0);

        return dto;
    }

    /**
     * Map WorkspaceMember entity to WorkspaceMemberDTO.
     *
     * @param m workspace member entity
     * @return member DTO
     */
    private WorkspaceMemberDTO mapToMemberDTO(WorkspaceMember m) {
        WorkspaceMemberDTO dto = new WorkspaceMemberDTO();
        dto.setId(m.getId());
        dto.setWorkspaceId(m.getWorkspaceId());
        dto.setUserId(m.getUserId());
        dto.setRole(m.getRole());
        dto.setCreateTime(m.getCreateTime());
        return dto;
    }

    public InvitationDTO createInvitation(Long workspaceId, CreateInvitationRequest req, Long actorUserId) {
        String role = req.getRole() != null ? req.getRole() : "MEMBER";
        int hours = req.getExpiresInHours() != null ? req.getExpiresInHours() : 72;
        WorkspaceInvitation inv = workspaceDomainService.createInvitation(workspaceId, actorUserId, role, hours);
        return mapToInvitationDTO(inv);
    }

    public List<InvitationDTO> listInvitations(Long workspaceId, Long actorUserId) {
        List<WorkspaceInvitation> invs = workspaceDomainService.listInvitations(workspaceId, actorUserId);
        if (invs == null || invs.isEmpty()) return Collections.emptyList();
        return invs.stream().map(this::mapToInvitationDTO).collect(Collectors.toList());
    }

    public void revokeInvitation(Long workspaceId, Long invitationId, Long actorUserId) {
        workspaceDomainService.revokeInvitation(workspaceId, invitationId, actorUserId);
    }

    public InvitationDTO getInvitationByToken(String token) {
        WorkspaceInvitation inv = workspaceDomainService.getInvitationByToken(token);
        if (inv == null) {
            return null;
        }
        return mapToInvitationDTO(inv);
    }

    public void acceptInvitation(String token, Long userId) {
        workspaceDomainService.acceptInvitation(token, userId);
    }

    private InvitationDTO mapToInvitationDTO(WorkspaceInvitation inv) {
        InvitationDTO dto = new InvitationDTO();
        dto.setId(inv.getId());
        dto.setWorkspaceId(inv.getWorkspaceId());
        dto.setCreatorId(inv.getCreatorId());
        dto.setToken(inv.getToken());
        dto.setRole(inv.getRole());
        dto.setExpiresAt(inv.getExpiresAt());
        dto.setMaxUses(inv.getMaxUses());
        dto.setUseCount(inv.getUseCount());
        dto.setIsActive(inv.getIsActive() == 1);
        dto.setCreateTime(inv.getCreateTime());

        // Resolve workspace name
        Workspace ws = workspaceDao.selectById(inv.getWorkspaceId());
        dto.setWorkspaceName(ws != null ? ws.getName() : "Unknown");

        // Resolve creator name
        UserInfo creator = userInfoDao.selectById(inv.getCreatorId());
        dto.setCreatorName(creator != null ? creator.getUsername() : "Unknown");

        return dto;
    }
}
