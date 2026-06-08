package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;

public class RenderModeChangePlanner {

    public ChangePlan plan(RenderMode mode) {
        return mode == null ? ChangePlan.ignorePlan() : ChangePlan.persistPlan(mode);
    }

    public record ChangePlan(boolean ignore, RenderMode modeToPersist) {

        private static ChangePlan ignorePlan() {
            return new ChangePlan(true, null);
        }

        private static ChangePlan persistPlan(RenderMode modeToPersist) {
            return new ChangePlan(false, modeToPersist);
        }
    }
}
