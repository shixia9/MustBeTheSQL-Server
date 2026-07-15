package com.sql.logic.engine.domain.workspace;

public enum WorkspaceRole {
    OWNER,
    ADMIN,
    MEMBER,
    VIEWER;

    public boolean canWrite() { return this == OWNER || this == ADMIN; }
    public boolean canManage() { return this == OWNER || this == ADMIN; }
    public boolean canDelete() { return this == OWNER; }
    public boolean canRead() { return true; }
}
