package com.ludo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ludo.entity.Room;
import com.ludo.entity.User;
import com.ludo.model.GameState;
import com.ludo.model.PlayerState;
import com.ludo.model.Token;
import com.ludo.repository.RoomRepository;
import com.ludo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class GameService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GameService.class);

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    private static final int TRACK_LENGTH = 52;
    private static final int HOME_POSITION = 57;
    private static final int TURN_SECONDS = 60;
    private static final int[] DICE_POOL = {1, 2, 3, 4, 5, 6, 6, 6};
    private static final SecureRandom DICE_RANDOM = new SecureRandom();
    // Clockwise around the board: GREEN -> YELLOW -> BLUE -> RED
    private static final Map<String, Integer> COLOR_OFFSETS = Map.of(
        "GREEN", 0,
        "YELLOW", 13,
        "BLUE", 26,
        "RED", 39
    );

    private static final Set<Integer> SAFE_INDICES = Set.of(0, 8, 13, 21, 26, 34, 39, 47);

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private GameService self;

    @Scheduled(fixedRate = 1000)
    public void processTurnTimers() {
        roomRepository.findAllByStatus(Room.RoomStatus.PLAYING).forEach(room -> {
            if (room.getGameState() == null) return; // skip rooms without state
            try {
                self.processRoomTimer(room.getCode());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.error("[TIMER-ERROR] GameState JSON is permanently corrupted for room: " + room.getCode() + ". Auto-finishing to self-heal. Error: " + e.getMessage(), e);
                try {
                    self.finishCorruptedRoom(room.getCode());
                } catch (Exception ex) {
                    log.error("[TIMER-ERROR] Failed to auto-finish room: " + room.getCode(), ex);
                }
            } catch (Exception e) {
                log.error("[TIMER-ERROR] Transient error processing timer for room: " + room.getCode() + ". Will retry in next tick. Error: " + e.getMessage());
            }
        });
    }

    @Transactional
    public void finishCorruptedRoom(String roomCode) {
        roomRepository.findByCode(roomCode).ifPresent(room -> {
            room.setStatus(Room.RoomStatus.FINISHED);
            roomRepository.saveAndFlush(room);
            log.info("[SELF-HEAL] Corrupted room " + roomCode + " successfully marked as FINISHED.");
        });
    }

    @Transactional
    public void processRoomTimer(String roomCode) throws JsonProcessingException {
        // Use regular findByCode for timers to avoid excessive locking
        Room room = roomRepository.findByCode(roomCode).orElse(null);
        if (room == null) return;
        if (room.getGameState() == null) return; // no state yet
        
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);
        if (!"PLAYING".equals(state.getStatus())) return;

        long now = System.currentTimeMillis();
        long elapsed = (now - state.getTurnStartTime()) / 1000;
        int remaining = Math.max(0, TURN_SECONDS - (int)elapsed);
        
        // Only update if time actually changed
            if (state.getRemainingTime() != remaining) {
            state.setRemainingTime(remaining);
            
            if (remaining <= 0) {
                String timedOutColor = state.getCurrentTurnColor();
                log.info("[TIMEOUT] Room " + roomCode + " player " + timedOutColor + " timed out.");
                endTurn(state, timedOutColor + " timed out. Turn skipped.");
                broadcastState(room, state);
            } else {
                // Save time update to DB without broadcasting every second (saves bandwidth)
                // Broadcast only on 5-second intervals for synchronization
                room.setGameState(objectMapper.writeValueAsString(state));
                roomRepository.save(room);
                if (remaining % 5 == 0 || remaining <= 5) {
                    messagingTemplate.convertAndSend("/topic/game/" + room.getCode(), state);
                }
            }
        }
    }

    @Scheduled(fixedRate = 3600000) // Every hour
    public void cleanupOldRooms() {
        // Only delete rooms that are FINISHED and created more than 24 hours ago
        long oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
        List<Room> toDelete = roomRepository.findAll().stream()
                .filter(r -> r.getStatus() == Room.RoomStatus.FINISHED && r.getCreatedAt() < oneDayAgo)
                .toList();
        
        if (!toDelete.isEmpty()) {
            log.info("[CLEANUP] Deleting " + toDelete.size() + " old finished rooms");
            roomRepository.deleteAll(toDelete);
        }
    }

    @Transactional
    public void rollDice(String roomCode, Long playerId) throws JsonProcessingException {
        // Lock the room during dice roll
        Room room = roomRepository.findByCodeWithLock(roomCode).orElseThrow();
        
        String playerColor = room.getPlayers().stream()
                .filter(p -> p.getId().equals(playerId))
                .findFirst()
                .map(com.ludo.entity.Player::getColor)
                .orElse(null);
                
        if (playerColor == null) return;
        
        if (room.getGameState() == null) return;
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);

        if (!"PLAYING".equals(state.getStatus()) || !state.getCurrentTurnColor().equals(playerColor) || state.isDiceRolled()) return;

        // Keep dice random, but make 6 more common so players can unlock tokens
        // without waiting too many dead turns. 6 has a 3/8 chance; 1-5 each have 1/8.
        int diceValue = DICE_POOL[DICE_RANDOM.nextInt(DICE_POOL.length)];
        
        log.info("[DICE] Rolled " + diceValue + " for " + playerColor);
        state.setDiceValue(diceValue);
        state.setLastDiceValue(diceValue);
        state.setDiceRolled(true);
        state.setRollId(System.currentTimeMillis());

        // Triple Six Rule
        if (diceValue == 6) {
            state.setConsecutiveSixes(state.getConsecutiveSixes() + 1);
            if (state.getConsecutiveSixes() == 3) {
                state.setConsecutiveSixes(0);
                endTurn(state, "TRIPLE 6! Turn Revoked.");
                broadcastState(room, state);
                return;
            }
        } else {
            state.setConsecutiveSixes(0);
        }

        // Automatic Skip if no moves possible
        boolean canMove = canPlayerMove(state, playerColor, diceValue);
        if (!canMove) {
            endTurn(state, playerColor + " rolled " + diceValue + " - no moves possible.");
        } else {
            state.setMessage(playerColor + " rolled " + diceValue + ". Choose your strategy.");
        }

        broadcastState(room, state);
    }

    @Transactional
    public void moveToken(String roomCode, Long playerId, int tokenId) throws JsonProcessingException {
        // Lock the room during token move
        Room room = roomRepository.findByCodeWithLock(roomCode).orElseThrow();
        
        String playerColor = room.getPlayers().stream()
                .filter(p -> p.getId().equals(playerId))
                .findFirst()
                .map(com.ludo.entity.Player::getColor)
                .orElse(null);
                
        if (playerColor == null) return;
        
        if (room.getGameState() == null) return;
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);

        if (!"PLAYING".equals(state.getStatus()) || !state.getCurrentTurnColor().equals(playerColor) || !state.isDiceRolled()) return;

        PlayerState ps = state.getPlayers().get(playerColor);
        if (ps == null || tokenId < 0 || tokenId >= ps.getTokens().size()) return;

        Token token = ps.getTokens().get(tokenId);
        int dice = state.getDiceValue();

        if (!isMoveLegal(state, playerColor, token, dice)) return;

        // Execute Move
        boolean killed = false;
        boolean reachedHomeThisTurn = false;
        int oldPosition = token.getPosition();

        if (token.getPosition() == -1) {
            token.setPosition(0);
        } else {
            token.setPosition(token.getPosition() + dice);
        }

        if (token.getPosition() == HOME_POSITION && oldPosition < HOME_POSITION) {
            reachedHomeThisTurn = true;
        }

        if (reachedHomeThisTurn) {
            final int tokenReward = 50;
            roomRepository.findByCode(roomCode).ifPresent(r -> {
                r.getPlayers().stream()
                        .filter(p -> p.getColor().equals(playerColor))
                        .findFirst()
                        .ifPresent(p -> {
                            User user = p.getUser();
                            if (user != null) {
                                user.addCoins(tokenReward);
                                userRepository.save(user);
                                ps.setCoins(user.getCoins());
                            }
                        });
            });
            state.setMessage("SPLENDID! " + playerColor + "'s token reached home! +50 Coins!");
        }

        // Reset roll animation ID after move choice
        state.setRollId(0);

        // Kills (Only on common track)
        updateTokenSafety(playerColor, token);

        if (token.getPosition() < TRACK_LENGTH) {
            killed = handleKills(state, playerColor, token);
        }

        // Win Verification
        boolean finished = false;
        if (token.getPosition() == HOME_POSITION) {
            finished = true;
            checkWinner(state, ps);
        }

        // Professional turn flow: only rolling a 6 grants another dice roll.
        // Captures and reaching home do not create duplicate turns.
        if ("FINISHED".equals(state.getStatus())) {
            state.setDiceRolled(false);
        } else if (ps.isHasFinished()) {
            endTurn(state);
        } else if (dice == 6) {
            grantExtraTurn(state, killed, finished);
        } else {
            endTurn(state);
        }

        broadcastState(room, state);
    }

    private boolean isMoveLegal(GameState state, String color, Token t, int dice) {
        if (t.getPosition() == -1) {
            return dice == 6 && !isOpponentBlockAt(state, color, getAbsoluteTrackIndex(color, 0));
        }
        if (t.getPosition() == HOME_POSITION) return false;

        int targetPosition = t.getPosition() + dice;
        if (targetPosition > HOME_POSITION) return false;

        // Once a token reaches the home lane it can only continue forward there;
        // it can never wrap back to the shared track or move sideways.
        if (t.getPosition() >= TRACK_LENGTH) return targetPosition <= HOME_POSITION;

        if (targetPosition >= TRACK_LENGTH) return true;

        return !isOpponentBlockAt(state, color, getAbsoluteTrackIndex(color, targetPosition));
    }

    private boolean handleKills(GameState state, String movingColor, Token movingToken) {
        int movingAbs = getAbsoluteTrackIndex(movingColor, movingToken.getPosition());
        if (SAFE_INDICES.contains(movingAbs)) return false;

        boolean killed = false;
        for (Map.Entry<String, PlayerState> entry : state.getPlayers().entrySet()) {
            if (entry.getKey().equals(movingColor)) continue;

            for (Token other : entry.getValue().getTokens()) {
                if (other.getPosition() >= 0 && other.getPosition() < TRACK_LENGTH) {
                    int otherAbs = getAbsoluteTrackIndex(entry.getKey(), other.getPosition());
                    if (otherAbs == movingAbs) {
                        other.setPosition(-1);
                        other.setSafe(false);
                        killed = true;
                    }
                }
            }
        }
        return killed;
    }

    private boolean canPlayerMove(GameState state, String color, int dice) {
        PlayerState player = state.getPlayers().get(color);
        return player != null && player.getTokens().stream().anyMatch(t -> isMoveLegal(state, color, t, dice));
    }

    private int getAbsoluteTrackIndex(String color, int relativePosition) {
        return (COLOR_OFFSETS.get(color) + relativePosition) % TRACK_LENGTH;
    }

    private boolean isOpponentBlockAt(GameState state, String movingColor, int absoluteTrackIndex) {
        for (Map.Entry<String, PlayerState> entry : state.getPlayers().entrySet()) {
            if (entry.getKey().equals(movingColor)) continue;

            long opponentTokensOnCell = entry.getValue().getTokens().stream()
                    .filter(t -> t.getPosition() >= 0 && t.getPosition() < TRACK_LENGTH)
                    .filter(t -> getAbsoluteTrackIndex(entry.getKey(), t.getPosition()) == absoluteTrackIndex)
                    .count();

            if (opponentTokensOnCell >= 2) return true;
        }
        return false;
    }

    private void updateTokenSafety(String color, Token token) {
        if (token.getPosition() < 0 || token.getPosition() >= TRACK_LENGTH) {
            token.setSafe(token.getPosition() >= TRACK_LENGTH && token.getPosition() <= HOME_POSITION);
            return;
        }
        token.setSafe(SAFE_INDICES.contains(getAbsoluteTrackIndex(color, token.getPosition())));
    }

    private void grantExtraTurn(GameState state, boolean killed, boolean finished) {
        state.setDiceRolled(false);
        state.setRollId(0);
        state.setTurnSequence(state.getTurnSequence() + 1);
        state.setTurnStartTime(System.currentTimeMillis());
        state.setRemainingTime(TURN_SECONDS);

        String detail = " rolled 6.";
        if (finished) {
            detail = " reached home with a 6.";
        } else if (killed) {
            detail = " rolled 6 and captured a token.";
        }
        state.setMessage(state.getCurrentTurnColor() + detail + " Extra turn.");
    }

    private void endTurn(GameState state) {
        endTurn(state, null);
    }

    private void endTurn(GameState state, String reason) {
        log.info("[TURN_SWITCH] Switching from " + state.getCurrentTurnColor());
        state.setDiceRolled(false);
        state.setRollId(0);
        state.setConsecutiveSixes(0);
        state.setTurnSequence(state.getTurnSequence() + 1);
        
        // Find next player who hasn't finished
        int currentIdx = state.getPlayerColors().indexOf(state.getCurrentTurnColor());
        int nextIdx = (currentIdx + 1) % state.getPlayerColors().size();
        int attempts = 0;
        
        while (attempts < state.getPlayerColors().size()) {
            String nextColor = state.getPlayerColors().get(nextIdx);
            if (!state.getPlayers().get(nextColor).isHasFinished()) {
                state.setCurrentTurnColor(nextColor);
                break;
            }
            nextIdx = (nextIdx + 1) % state.getPlayerColors().size();
            attempts++;
        }

        if (attempts >= state.getPlayerColors().size()) {
            state.setStatus("FINISHED");
            state.setMessage("All players have completed the conquest!");
        } else {
            String nextTurnMessage = "It's " + state.getCurrentTurnColor() + "'s turn";
            state.setMessage(reason == null ? nextTurnMessage : reason + " " + nextTurnMessage);
            state.setTurnStartTime(System.currentTimeMillis());
            state.setRemainingTime(TURN_SECONDS);
        }
        
        log.info("[TURN_SWITCH] Now it's " + state.getCurrentTurnColor() + "'s turn.");
    }

    private void checkWinner(GameState state, PlayerState ps) {
        if (ps.isHasFinished()) {
            return; // Already ranked and rewarded, avoid duplicate coins
        }
        if (ps.getTokens().stream().allMatch(t -> t.getPosition() == HOME_POSITION)) {
            ps.setHasFinished(true);
            if (!state.getRankings().contains(ps.getColor())) {
                state.getRankings().add(ps.getColor());
            }
            
            int rank = state.getRankings().size();
            ps.setRank(rank);
            String suffix = rank == 1 ? "ST" : (rank == 2 ? "ND" : (rank == 3 ? "RD" : "TH"));
            state.setMessage("LEGENDARY! " + ps.getColor() + " secures " + rank + suffix + " place!");
            
            if (rank == 1) {
                state.setWinnerColor(ps.getColor());
            }

            // Check if game should end (only 1 player left)
            long activePlayers = state.getPlayers().values().stream()
                    .filter(p -> !p.isHasFinished())
                    .count();
            
            if (activePlayers <= 1) {
                state.setStatus("FINISHED");
                state.setMessage("GAME OVER! All ranks decided.");
                // Assign last rank to the remaining player
                state.getPlayers().values().stream()
                        .filter(p -> !p.isHasFinished())
                        .findFirst()
                        .ifPresent(lastPlayer -> {
                            lastPlayer.setHasFinished(true);
                            if (!state.getRankings().contains(lastPlayer.getColor())) {
                                state.getRankings().add(lastPlayer.getColor());
                            }
                            int lastRank = state.getRankings().size();
                            lastPlayer.setRank(lastRank);
                        });
            }
            
            int reward = rank == 1 ? 500 : (rank == 2 ? 250 : 100);
            
            roomRepository.findByCode(state.getRoomCode()).ifPresent(room -> {
                room.getPlayers().stream()
                        .filter(p -> p.getColor().equals(ps.getColor()))
                        .findFirst()
                        .ifPresent(p -> {
                            User user = p.getUser();
                            if (user != null) {
                                user.addCoins(reward);
                                userRepository.save(user);
                                ps.setCoins(user.getCoins());
                            }
                        });
            });
        }
    }

    private void broadcastState(Room room, @org.springframework.lang.NonNull GameState state) throws JsonProcessingException {
        if ("FINISHED".equals(state.getStatus())) {
            room.setStatus(Room.RoomStatus.FINISHED);
        }
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.save(room);
        messagingTemplate.convertAndSend("/topic/game/" + room.getCode(), state);
    }
}
