package edu.oswego.cs.client;

import java.util.Arrays;
import java.util.Optional;

public enum Command {
    EXIT(".exit", ".leave", ".quit"),
    CHAT(".chat"),
    MOVE(".move"),
    PICKUP(".pickup", ".grab"),
    ATTACK(".fight", ".attack"),
    LOOK(".look", "ls"),
    USE(".use"),
    DESCEND(".descend");


    public final String[] names;

    private Command(String... names) {
        this.names = names;
    }

    public static Optional<Command> parse(String command) {
        return Arrays.stream(values())
                .filter(commandNames -> Arrays.stream(commandNames.names)
                        .filter(name -> name.equals(command)).toArray().length == 1)
                .findFirst();
    }
}
