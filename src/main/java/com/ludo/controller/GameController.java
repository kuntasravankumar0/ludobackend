package com.ludo.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ludo.service.GameService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class GameController {

    private final GameService gameService;

    @MessageMapping("/game/{roomCode}/roll")
    public void rollDice(@DestinationVariable String roomCode, RollRequest request) throws JsonProcessingException {
        gameService.rollDice(roomCode, request.getPlayerId());
    }

    @MessageMapping("/game/{roomCode}/move")
    public void moveToken(@DestinationVariable String roomCode, MoveRequest request) throws JsonProcessingException {
        gameService.moveToken(roomCode, request.getPlayerId(), request.getTokenId());
    }

    @Data
    public static class RollRequest {
        private Long playerId;
    }

    @Data
    public static class MoveRequest {
        private Long playerId;
        private int tokenId;
    }
}