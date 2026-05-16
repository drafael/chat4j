package com.github.drafael.chat4j.chat.message;

import com.github.drafael.chat4j.provider.api.Role;

import java.util.function.IntSupplier;

interface MessageContentViewProvider {

    MessageContentView create(Role role, IntSupplier maxContentWidthSupplier);
}
