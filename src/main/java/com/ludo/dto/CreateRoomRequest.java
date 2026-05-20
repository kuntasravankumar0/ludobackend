package com.ludo.dto;

import lombok.Data;

@Data
public class CreateRoomRequest {
    private String hostName;
    private int maxPlayers;
    private String gameMode; // "CLASSIC", "2V2"
}
