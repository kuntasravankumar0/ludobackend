package com.ludo.controller;

import com.ludo.dto.CreateRoomRequest;
import com.ludo.dto.JoinRoomRequest;
import com.ludo.entity.Room;
import com.ludo.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
@CrossOrigin()
@RequiredArgsConstructor
public class RoomController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RoomController.class);

    private final RoomService roomService;

    @PostMapping("/create")
    public ResponseEntity<?> createRoom(@RequestBody CreateRoomRequest request) {
        try {
            return ResponseEntity
                    .ok(roomService.createRoom(request.getHostName(), request.getMaxPlayers(), request.getGameMode()));
        } catch (Exception e) {
            log.warn("Create room failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/join")
    public ResponseEntity<?> joinRoom(@RequestBody JoinRoomRequest request) {
        try {
            return ResponseEntity.ok(roomService.joinRoom(request.getRoomCode(), request.getPlayerName()));
        } catch (Exception e) {
            log.warn("Join room failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/{code}")
    public ResponseEntity<Room> getRoom(@PathVariable String code) {
        return ResponseEntity.ok(roomService.getRoomByCode(code));
    }

    @PostMapping("/{code}/start")
    public ResponseEntity<?> startGame(@PathVariable String code) {
        try {
            com.ludo.entity.Room room = roomService.startGame(code);
            return ResponseEntity.ok(java.util.Map.of(
                "status", room.getStatus().toString(),
                "code", room.getCode(),
                "gameState", room.getGameState()
            ));
        } catch (Exception e) {
            log.warn("Start game failed for room {}: {}", code, e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
