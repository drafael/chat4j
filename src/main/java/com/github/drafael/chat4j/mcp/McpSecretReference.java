package com.github.drafael.chat4j.mcp;

import org.apache.commons.lang3.StringUtils;

public record McpSecretReference(String rowId, String key, String secretId) {

    public McpSecretReference {
        rowId = StringUtils.defaultString(rowId);
        key = StringUtils.defaultString(key);
        secretId = StringUtils.defaultString(secretId);
    }
}
