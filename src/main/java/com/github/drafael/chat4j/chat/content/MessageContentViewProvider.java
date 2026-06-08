package com.github.drafael.chat4j.chat.content;

import com.github.drafael.chat4j.provider.api.Role;

import java.util.function.IntSupplier;

public interface MessageContentViewProvider {

    MessageContentView create(Role role, IntSupplier maxContentWidthSupplier);
}
