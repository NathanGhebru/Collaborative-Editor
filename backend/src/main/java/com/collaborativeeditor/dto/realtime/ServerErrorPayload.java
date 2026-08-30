package com.collaborativeeditor.dto.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerErrorPayload(
    @JsonProperty("code") String code,
    @JsonProperty("message") String message,
    @JsonProperty("fatal") boolean fatal,
    @JsonProperty("closeCode") Integer closeCode
) {}
