package com.umamimitra.link;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Finds a project folder (like web/galley) no matter which working directory IntelliJ starts the program in. */
public final class Folders {
    private Folders() {}

    public static Path find(String relative, String systemProperty) {
        String forced = System.getProperty(systemProperty);
        if (forced != null && !forced.isBlank()) return Paths.get(forced).toAbsolutePath().normalize();
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isDirectory(candidate)) return candidate.normalize();
        }
        throw new IllegalStateException("Cannot find the folder '" + relative + "'. Start the program with the project folder as working directory.");
    }
}
