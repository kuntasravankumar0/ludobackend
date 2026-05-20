package com.ludo.model;

import lombok.Data;

@Data
public class Token {
    private int id; // 0, 1, 2, 3
    private int position; // -1 for home, 0-56 for track, 57 for goal
    private boolean isSafe; // true if in a safe zone
}
