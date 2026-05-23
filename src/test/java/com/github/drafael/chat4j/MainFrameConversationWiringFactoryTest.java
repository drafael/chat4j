package com.github.drafael.chat4j;

import com.github.drafael.chat4j.storage.ConversationRepo;
import com.github.drafael.chat4j.storage.PersistedMessageCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainFrameConversationWiringFactoryTest {

    @Test
    @DisplayName("Create builds conversation wiring without render-mode persistence")
    void create_whenCalled_buildsConversationWiring() throws Exception {
        var subject = new MainFrameConversationWiringFactory();
        var wiring = subject.create(new ConversationRepo(null), new PersistedMessageCounter());

        assertThat(wiring.conversationLoadCoordinator()).isNotNull();
        assertThat(wiring.conversationLoadResultPlanner()).isNotNull();
        assertThat(wiring.conversationLoadApplyDispatchCoordinator()).isNotNull();
        assertThat(wiring.conversationPersistenceCoordinator()).isNotNull();
        assertThat(wiring.assistantMessageCompletionFlowCoordinator()).isNotNull();
        assertThat(wiring.currentConversationSaveCoordinator()).isNotNull();

    }

    @Test
    @DisplayName("Create validates required arguments")
    void create_whenRequiredArgumentMissing_throwsException() {
        var subject = new MainFrameConversationWiringFactory();

        assertThatThrownBy(() -> subject.create(null, new PersistedMessageCounter()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("conversationRepo");
    }
}
