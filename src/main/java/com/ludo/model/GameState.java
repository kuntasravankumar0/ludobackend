package com.ludo.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class GameState {
    private String roomCode;
    private String status; // WAITING, PLAYING, FINISHED
    private List<String> playerColors; // Colors of players in order of turns
    private String currentTurnColor;
    private int diceValue = 1;
    private int lastDiceValue = 1;
    private boolean diceRolled;
    private long rollId;
    private int turnSequence;
    private int consecutiveSixes;
    private Map<String, PlayerState> players; // color -> PlayerState
    private String winnerColor;
    private List<String> rankings = new java.util.ArrayList<>(); // Order of finishing
    private String message;
    private long turnStartTime;
    private int remainingTime = 60; // 60 seconds per turn
}
