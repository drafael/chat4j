package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

final class CodexCliApi {

    private CodexCliApi() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(Integer id, String method, Item item, Params params, Result result, RpcError error) {
        Item eventItem() {
            return item != null ? item : params == null ? null : params.item();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Params(String delta, Item item, Turn turn, RpcError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(ThreadResult thread) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ThreadResult(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Turn(String status, RpcError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RpcError(String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(String type, String query, Action action, List<Action> actions, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Action(String type, String query, List<String> queries, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record JwtClaims(
            @JsonProperty("https://api.openai.com/auth") AuthClaims auth,
            @JsonProperty("https://api.openai.com/auth.chatgpt_account_id")
            @JsonAlias("chatgpt_account_id") String accountId
    ) {
        String chatGptAccountId() {
            return auth != null && auth.chatGptAccountId() != null ? auth.chatGptAccountId() : accountId;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthClaims(@JsonProperty("chatgpt_account_id") String chatGptAccountId) {
    }
}
