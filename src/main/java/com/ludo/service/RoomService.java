package com.ludo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ludo.entity.Player;
import com.ludo.entity.Room;
import com.ludo.entity.User;
import com.ludo.model.GameState;
import com.ludo.model.PlayerState;
import com.ludo.model.Token;
import com.ludo.repository.RoomRepository;
import com.ludo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class RoomService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RoomService.class);

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    private static final String[] COLORS = {"RED", "GREEN", "YELLOW", "BLUE"};

    @Transactional
    public Room createRoom(String hostName, int maxPlayers, String gameMode) {
        String cleanHostName = normalizeRequired(hostName, "Host name");
        String cleanGameMode = normalizeGameMode(gameMode);
        int playerLimit = normalizePlayerLimit(maxPlayers, cleanGameMode);

        User user = userRepository.findByName(cleanHostName).orElseGet(() -> {
            User newUser = new User();
            newUser.setName(cleanHostName);
            return userRepository.save(newUser);
        });

        String code = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Room room = new Room();
        room.setCode(code);
        room.setMaxPlayers(playerLimit);
        room.setGameMode(cleanGameMode);
        room.setStatus(Room.RoomStatus.WAITING);

        Player host = new Player();
        host.setName(cleanHostName);
        host.setColor(COLORS[0]);
        host.setRoom(room);
        host.setUser(user);
        room.getPlayers().add(host);

        Room savedRoom = roomRepository.saveAndFlush(room);
        broadcastRoomUpdate(savedRoom);
        return savedRoom;
    }

    @Transactional
    public Room joinRoom(String roomCode, String playerName) {
        String cleanRoomCode = normalizeRequired(roomCode, "Room code").toUpperCase();
        String cleanPlayerName = normalizeRequired(playerName, "Player name");

        // Use interning to synchronize on the room code string
        synchronized (cleanRoomCode.intern()) {
            Room room = roomRepository.findByCodeWithLock(cleanRoomCode)
                    .orElseThrow(() -> new RuntimeException("Room not found"));

        if (room.getStatus() != Room.RoomStatus.WAITING && room.getStatus() != Room.RoomStatus.PLAYING) {
            throw new RuntimeException("Cannot join room in current status: " + room.getStatus());
        }

        // Check if player is already in the room
        Optional<Player> existing = room.getPlayers().stream()
                .filter(p -> p.getName().equalsIgnoreCase(cleanPlayerName))
                .findFirst();
        
        if (existing.isPresent()) {
            log.info("[ROOM] Player " + cleanPlayerName + " re-joining room " + cleanRoomCode);
            return room; // findByCodeWithLock already gives us the latest room
        }

        if (room.getStatus() == Room.RoomStatus.PLAYING) {
            throw new RuntimeException("Battle in progress! Cannot join new warriors now.");
        }

        if (room.getPlayers().size() >= room.getMaxPlayers()) {
            throw new RuntimeException("This kingdom is already full (" + room.getMaxPlayers() + " warriors).");
        }

        User user = userRepository.findByName(cleanPlayerName).orElseGet(() -> {
            User newUser = new User();
            newUser.setName(cleanPlayerName);
            return userRepository.save(newUser);
        });

        Player player = new Player();
        player.setName(cleanPlayerName);
        player.setColor(COLORS[room.getPlayers().size()]);
        player.setRoom(room);
        player.setUser(user);
        room.getPlayers().add(player);

            Room savedRoom = roomRepository.saveAndFlush(room);
            
            // Ensure ordering for the broadcast
            savedRoom.getPlayers().sort(Comparator.comparing(Player::getId));
            
            broadcastRoomUpdate(savedRoom);
            return savedRoom;
        }
    }

    public Room getRoomByCode(String code) {
        return roomRepository.findByCode(code)
                .orElseThrow(() -> new RuntimeException("Room not found"));
    }

    @Transactional
    public Room startGame(String roomCode) throws JsonProcessingException {
        String cleanRoomCode = normalizeRequired(roomCode, "Room code").toUpperCase();
        Room room = roomRepository.findByCodeWithLock(cleanRoomCode)
                .orElseThrow(() -> new RuntimeException("Room not found"));
        if (room.getPlayers().size() < 2) {
            throw new RuntimeException("Minimum 2 players required to start");
        }
        if ("2V2".equals(room.getGameMode()) && room.getPlayers().size() != 4) {
            throw new RuntimeException("Alliance 2v2 requires exactly 4 players");
        }
        if (room.getStatus() == Room.RoomStatus.PLAYING && room.getGameState() != null) {
            return room;
        }

        room.setStatus(Room.RoomStatus.PLAYING);
        
        // Sort players by ID to ensure consistent turn order
        List<Player> sortedPlayers = room.getPlayers().stream()
                .sorted(Comparator.comparing(Player::getId))
                .toList();

        // Initialize Game State
        GameState gameState = new GameState();
        gameState.setRoomCode(cleanRoomCode);
        gameState.setStatus("PLAYING");
        gameState.setPlayerColors(sortedPlayers.stream().map(Player::getColor).toList());
        gameState.setCurrentTurnColor(gameState.getPlayerColors().get(0));
        gameState.setDiceValue(1);
        gameState.setDiceRolled(false);
        gameState.setMessage("It's " + gameState.getCurrentTurnColor() + "'s turn");
        gameState.setTurnStartTime(System.currentTimeMillis());

        Map<String, PlayerState> playerStates = new HashMap<>();
        for (Player p : room.getPlayers()) {
            PlayerState ps = new PlayerState();
            ps.setName(p.getName());
            ps.setColor(p.getColor());
            ps.setCoins(p.getUser() != null ? p.getUser().getCoins() : 0);
            ps.setHasFinished(false);
            
            List<Token> tokens = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Token t = new Token();
                t.setId(i);
                t.setPosition(-1); // At home
                t.setSafe(false);
                tokens.add(t);
            }
            ps.setTokens(tokens);
            playerStates.put(p.getColor(), ps);
        }
        gameState.setPlayers(playerStates);

        room.setGameState(objectMapper.writeValueAsString(gameState));
        roomRepository.saveAndFlush(room);
        
        // Broadcast that game has started
        messagingTemplate.convertAndSend("/topic/game/" + cleanRoomCode, gameState);
        // Ensure players are sorted before broadcasting start
        room.getPlayers().sort(Comparator.comparing(Player::getId));
        broadcastRoomUpdate(room); // Update room status in lobby too
        return room;
    }

    private void broadcastRoomUpdate(Room room) {
        messagingTemplate.convertAndSend("/topic/room/" + room.getCode(), room);
    }

    private String normalizeRequired(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new RuntimeException(fieldName + " is required");
        }
        return value.trim();
    }

    private String normalizeGameMode(String gameMode) {
        String cleanGameMode = gameMode == null || gameMode.trim().isEmpty()
                ? "CLASSIC"
                : gameMode.trim().toUpperCase();

        if (!"CLASSIC".equals(cleanGameMode) && !"2V2".equals(cleanGameMode)) {
            throw new RuntimeException("Unsupported game mode: " + gameMode);
        }

        return cleanGameMode;
    }

    private int normalizePlayerLimit(int maxPlayers, String gameMode) {
        if ("2V2".equals(gameMode)) {
            return 4;
        }
        if (maxPlayers < 2 || maxPlayers > COLORS.length) {
            throw new RuntimeException("Classic rooms support 2, 3, or 4 players");
        }
        return maxPlayers;
    }
}
