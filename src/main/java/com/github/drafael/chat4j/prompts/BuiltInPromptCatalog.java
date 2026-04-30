package com.github.drafael.chat4j.prompts;

import java.util.List;

public final class BuiltInPromptCatalog {

    private BuiltInPromptCatalog() {
    }

    public static List<PromptTemplate> prompts() {
        return List.of(
                new PromptTemplate(
                        "change-length",
                        "Change length",
                        "Change the length of the following text to be @{{length}}, return the updated text only:\n\n@{{text}}",
                        PromptTemplate.DEFAULT_MODEL,
                        List.of(
                                PromptVariable.input("text"),
                                PromptVariable.select("length", List.of("shorter", "longer"))
                        )
                ),
                new PromptTemplate(
                        "change-tone",
                        "Change tone",
                        "Change the following text to a @{{tone}} tone, return the updated text only:\n\n@{{text}}",
                        PromptTemplate.DEFAULT_MODEL,
                        List.of(
                                PromptVariable.input("text"),
                                PromptVariable.select(
                                        "tone",
                                        List.of(
                                                "Academic",
                                                "Professional",
                                                "Persuasive",
                                                "Funny",
                                                "Casual",
                                                "Friendly",
                                                "Sarcastic"
                                        )
                                )
                        )
                ),
                new PromptTemplate(
                        "explain",
                        "Explain",
                        "Explain the following text, return the explanation only:\n\n@{{text}}",
                        PromptTemplate.DEFAULT_MODEL,
                        List.of(PromptVariable.input("text"))
                ),
                new PromptTemplate(
                        "fix-grammar",
                        "Fix grammar",
                        "Fix grammar in the following text, return the updated text only:\n\n@{{text}}",
                        PromptTemplate.DEFAULT_MODEL,
                        List.of(PromptVariable.input("text"))
                ),
                new PromptTemplate(
                        "improve-writing",
                        "Improve writing",
                        "Improve writing in the following text, return the updated text only:\n\n@{{text}}",
                        PromptTemplate.DEFAULT_MODEL,
                        List.of(PromptVariable.input("text"))
                ),
                new PromptTemplate(
                        "summarize",
                        "Summarize",
                        "Summarize the following text, return the summary only:\n\n@{{text}}",
                        PromptTemplate.DEFAULT_MODEL,
                        List.of(PromptVariable.input("text"))
                ),
                new PromptTemplate(
                        "translate",
                        "Translate",
                        "Translate to @{{lang}}, return the translated text only:\n\n@{{text}}",
                        PromptTemplate.DEFAULT_MODEL,
                        List.of(
                                PromptVariable.input("text"),
                                PromptVariable.select(
                                        "lang",
                                        List.of(
                                                "English",
                                                "Spanish",
                                                "French",
                                                "German",
                                                "Italian",
                                                "Portuguese",
                                                "Russian",
                                                "Turkish",
                                                "Vietnamese",
                                                "Dutch",
                                                "Indonesian",
                                                "Philippine",
                                                "Ukrainian"
                                        )
                                )
                        )
                )
        );
    }
}
