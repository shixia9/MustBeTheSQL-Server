package com.sql.logic.engine.trigger.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelListResponse {
    private String object = "list";
    private List<Model> data;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Model {
        private String id;
        private String object = "model";
        private Long created;
        @JsonProperty("owned_by")
        private String ownedBy;
    }

    public static Model of(String id, Long created, String ownedBy) {
        Model m = new Model();
        m.setId(id);
        m.setCreated(created);
        m.setOwnedBy(ownedBy);
        return m;
    }
}
