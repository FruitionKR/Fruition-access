package fruition.access.workspace.domain;

import jakarta.persistence.AttributeConverter;

/** enum 상수명이 아니라 "wiki.ingest" 표기로 저장한다. */
public class WorkspacePermissionConverter implements AttributeConverter<WorkspacePermission, String> {

    @Override
    public String convertToDatabaseColumn(WorkspacePermission attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public WorkspacePermission convertToEntityAttribute(String dbData) {
        return dbData == null ? null : WorkspacePermission.fromValue(dbData);
    }
}
