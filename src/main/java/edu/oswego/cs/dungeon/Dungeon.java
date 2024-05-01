package edu.oswego.cs.dungeon;

import edu.oswego.cs.game.GameCommandOutput;

import java.util.ArrayList;
import java.util.HashMap;

public class Dungeon {
    private ArrayList<Floor> floors;
    private HashMap<String, GameUser> currentUsers;
    private long seed;

    public void addUser(GameUser gameUser) {
        if(currentUsers == null) currentUsers = new HashMap<>();
        currentUsers.put(gameUser.username, gameUser);
    }

    public Dungeon(long seed) {
        floors = new ArrayList<>();
        this.seed = seed;
    }

    public Floor makeFloor() {
        FloorGenerator generator = new FloorGenerator(seed);
        Floor newFloor = generator.generate(floors.size() + 1);
        floors.add(newFloor);
        updateSeed();
        return newFloor;
    }

    private void updateSeed() {
        seed ^= seed << 13;
        seed ^= seed >>> 7;
        seed ^= seed << 17;
    }

    //TODO: Send back output text if someone entered your room
    public GameCommandOutput move(String username, char direction) {
        GameCommandOutput output = new GameCommandOutput(username, "Can't move that way!", false);

        GameUser gameUser = currentUsers.get(username);
        char directionCaps = Character.toUpperCase(direction);

        switch(directionCaps) {
            case 'N':
                if(gameUser.currentRoom.northExit == null) break;

                gameUser.currentRoom = gameUser.currentRoom.northExit;
                output.successful = true;
                break;
            case 'S':
                if(gameUser.currentRoom.southExit == null) break;

                gameUser.currentRoom = gameUser.currentRoom.southExit;
                output.successful = true;
                break;
            case 'E':
                if(gameUser.currentRoom.eastExit == null) break;

                gameUser.currentRoom = gameUser.currentRoom.eastExit;
                output.successful = true;
                break;
            case 'W':
                if(gameUser.currentRoom.westExit == null) break;

                gameUser.currentRoom = gameUser.currentRoom.westExit;
                output.successful = true;
                break;
            default:
                break;
        }

        if(output.successful) {
            output.textOutput = "Moved " + direction + ". Current room: " + gameUser.currentRoom.prettyRoomNumber() + ".";
        }

        return output;
    }

    public GameCommandOutput attack(String username, String target) {
        GameCommandOutput output = new GameCommandOutput(username, "", false);

        GameUser gameUser = currentUsers.get(username);
        if(gameUser.currentRoom.entities.isEmpty()) {
            output.textOutput = "Nothing here to attack!";
            return output;
        }

        Entity entity = null;


        return output;
    }
}
