package com.github.drafael.chat4j.persistence.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelFavoriteKeyCodecTest {

    @Test
    @DisplayName("Favorite key codec preserves the persisted key format")
    void toFavoriteKey_whenModelContainsReservedCharacters_encodesOnlyModelId() {
        var modelRef = new ModelFavoritesService.ModelRef(" LM Studio ", " qwen3:14b ");

        String key = ModelFavoriteKeyCodec.toFavoriteKey(modelRef);

        assertThat(key).isEqualTo("chat4j.models.favorite.lm-studio::qwen3%3A14b");
    }

    @Test
    @DisplayName("Favorite key codec rejects invalid model references")
    void toFavoriteKey_whenModelReferenceIsInvalid_rejectsReference() {
        var blankModelRef = new ModelFavoritesService.ModelRef("OpenAI", " ");

        assertThatThrownBy(() -> ModelFavoriteKeyCodec.toFavoriteKey(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelFavoriteKeyCodec.toFavoriteKey(blankModelRef))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Favorite key codec parses persisted favorites into normalized model refs")
    void parseFavoriteKey_whenKeyUsesPersistedFormat_returnsNormalizedModelRef() {
        ModelFavoritesService.ModelRef modelRef = ModelFavoriteKeyCodec.parseFavoriteKey(
                "chat4j.models.favorite.lm-studio::qwen3%3A14b"
        );

        assertThat(modelRef).isEqualTo(new ModelFavoritesService.ModelRef("lm-studio", "qwen3:14b"));
    }

    @Test
    @DisplayName("Favorite key codec ignores unrelated or malformed keys")
    void parseFavoriteKey_whenKeyIsMalformed_returnsNull() {
        assertThat(ModelFavoriteKeyCodec.parseFavoriteKey(null)).isNull();
        assertThat(ModelFavoriteKeyCodec.parseFavoriteKey("chat4j.ui.theme.name")).isNull();
        assertThat(ModelFavoriteKeyCodec.parseFavoriteKey("chat4j.models.favorite.openai")).isNull();
        assertThat(ModelFavoriteKeyCodec.parseFavoriteKey("chat4j.models.favorite.openai::bad%zzmodel")).isNull();
    }
}
