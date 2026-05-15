package com.github.drafael.chat4j.chat;

import java.awt.Component;
import java.nio.file.Path;
import java.util.Optional;

@FunctionalInterface
interface ProjectRootChooser {
    Optional<Path> choose(Component parent);
}
