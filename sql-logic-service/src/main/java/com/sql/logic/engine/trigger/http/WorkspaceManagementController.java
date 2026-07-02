package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.WorkspaceManagementAppService;
import com.sql.logic.engine.common.dto.AddMemberRequest;
import com.sql.logic.engine.common.dto.CreateInvitationRequest;
import com.sql.logic.engine.common.dto.CreateWorkspaceRequest;
import com.sql.logic.engine.common.dto.InvitationDTO;
import com.sql.logic.engine.common.dto.UpdateMemberRoleRequest;
import com.sql.logic.engine.common.dto.WorkspaceDTO;
import com.sql.logic.engine.common.dto.WorkspaceMemberDTO;
import com.sql.logic.engine.common.response.Result;

import cn.dev33.satoken.stp.StpUtil;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceManagementController {

    private final WorkspaceManagementAppService workspaceManagementAppService;

    public WorkspaceManagementController(WorkspaceManagementAppService workspaceManagementAppService) {
        this.workspaceManagementAppService = workspaceManagementAppService;
    }

    // GET /api/v1/workspaces — list my workspaces
    @GetMapping
    public Result<List<WorkspaceDTO>> listWorkspaces() {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(400, "Invalid user ID in session");
        return Result.success(workspaceManagementAppService.listMyWorkspaces(userId));
    }

    // POST /api/v1/workspaces — create workspace
    @PostMapping
    public Result<WorkspaceDTO> createWorkspace(@RequestBody CreateWorkspaceRequest request) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(400, "Invalid user ID in session");
        if (request.getName() == null || request.getName().isBlank()) {
            return Result.error(400, "Workspace name is required");
        }
        try {
            return Result.success(workspaceManagementAppService.createWorkspace(request, userId));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    // PUT /api/v1/workspaces/{id} — update workspace
    @PutMapping("/{id}")
    public Result<WorkspaceDTO> updateWorkspace(@PathVariable Long id, @RequestBody CreateWorkspaceRequest request) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(400, "Invalid user ID in session");
        try {
            return Result.success(workspaceManagementAppService.updateWorkspace(id, request, userId));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    // DELETE /api/v1/workspaces/{id} — delete workspace
    @DeleteMapping("/{id}")
    public Result<Void> deleteWorkspace(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(400, "Invalid user ID in session");
        try {
            workspaceManagementAppService.deleteWorkspace(id, userId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    // GET /api/v1/workspaces/{id}/members — list members
    @GetMapping("/{id}/members")
    public Result<List<WorkspaceMemberDTO>> listMembers(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(400, "Invalid user ID in session");
        try {
            return Result.success(workspaceManagementAppService.listMembers(id, userId));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    // POST /api/v1/workspaces/{id}/members — add member
    @PostMapping("/{id}/members")
    public Result<Void> addMember(@PathVariable Long id, @RequestBody AddMemberRequest request) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(400, "Invalid user ID in session");
        if (request.getUserId() == null) {
            return Result.error(400, "userId is required");
        }
        try {
            workspaceManagementAppService.addMember(id, request, userId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    // PUT /api/v1/workspaces/{id}/members/{memberUserId} — update member role
    @PutMapping("/{id}/members/{memberUserId}")
    public Result<Void> updateMemberRole(@PathVariable Long id, @PathVariable Long memberUserId, @RequestBody UpdateMemberRoleRequest request) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(400, "Invalid user ID in session");
        if (request.getRole() == null || request.getRole().isBlank()) {
            return Result.error(400, "role is required");
        }
        try {
            workspaceManagementAppService.updateMemberRole(id, memberUserId, request, userId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    // DELETE /api/v1/workspaces/{id}/members/{memberUserId} — remove member
    @DeleteMapping("/{id}/members/{memberUserId}")
    public Result<Void> removeMember(@PathVariable Long id, @PathVariable Long memberUserId) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(400, "Invalid user ID in session");
        try {
            workspaceManagementAppService.removeMember(id, memberUserId, userId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    // POST /api/v1/workspaces/{id}/invitations — create invitation link
    @PostMapping("/{id}/invitations")
    public Result<InvitationDTO> createInvitation(@PathVariable Long id, @RequestBody CreateInvitationRequest request) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(400, "Invalid user ID in session");
        try {
            InvitationDTO inv = workspaceManagementAppService.createInvitation(id, request, userId);
            return Result.success(inv);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    // GET /api/v1/workspaces/{id}/invitations — list invitation links
    @GetMapping("/{id}/invitations")
    public Result<List<InvitationDTO>> listInvitations(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(400, "Invalid user ID in session");
        try {
            List<InvitationDTO> list = workspaceManagementAppService.listInvitations(id, userId);
            return Result.success(list);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    // DELETE /api/v1/workspaces/{id}/invitations/{invitationId} — revoke invitation
    @DeleteMapping("/{id}/invitations/{invitationId}")
    public Result<Void> revokeInvitation(@PathVariable Long id, @PathVariable Long invitationId) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(400, "Invalid user ID in session");
        try {
            workspaceManagementAppService.revokeInvitation(id, invitationId, userId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    // GET /api/v1/workspaces/invitations/{token} — get invitation details (public, no auth required)
    @GetMapping("/invitations/{token}")
    public Result<InvitationDTO> getInvitationByToken(@PathVariable String token) {
        try {
            InvitationDTO inv = workspaceManagementAppService.getInvitationByToken(token);
            if (inv == null) {
                return Result.error(404, "Invitation not found");
            }
            return Result.success(inv);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    // POST /api/v1/workspaces/invitations/{token}/accept — accept invitation (requires auth)
    @PostMapping("/invitations/{token}/accept")
    public Result<Void> acceptInvitation(@PathVariable String token) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(400, "Invalid user ID in session");
        try {
            workspaceManagementAppService.acceptInvitation(token, userId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    private Long getCurrentUserId() {
        String id = (String) StpUtil.getLoginId();
        if (id == null || !id.matches("\\d+")) return null;
        return Long.valueOf(id);
    }
}
