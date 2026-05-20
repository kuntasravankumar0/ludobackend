package com.ludo.model;

import lombok.Data;
import java.util.List;

@Data
public class PlayerState {
    private String name;
    private String color;
    private List<Token> tokens; // Usually 4 tokens
    private boolean hasFinished;
    private int rank;
    private long coins;
}
