package com.ludo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ludo.entity.Player;
import com.ludo.entity.Room;
import com.ludo.entity.User;
import com.ludo.model.GameState;
import com.ludo.model.PlayerState;
import com.ludo.model.Token;
import com.ludo.repository.RoomRepository;
import com.ludo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
public class GameServiceTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameService gameService;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Room room;
    private String roomCode;

    @BeforeEach
    public void setup() throws Exception {
        userRepository.deleteAll();
        roomRepository.deleteAll();

        // Create room and join another player to start a 2-player game
        room = roomService.createRoom("Hero", 2, "CLASSIC");
        roomCode = room.getCode();
        room = roomService.joinRoom(roomCode, "Champion");
    }

    @Test
    public void testStartGameCreatesCorrectGameState() throws Exception {
        room = roomService.startGame(roomCode);
        assertEquals(Room.RoomStatus.PLAYING, room.getStatus());
        assertNotNull(room.getGameState());

        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);
        assertEquals("PLAYING", state.getStatus());
        assertEquals(2, state.getPlayers().size());
        assertTrue(state.getPlayerColors().contains("RED"));
        assertTrue(state.getPlayerColors().contains("GREEN"));
        assertEquals("RED", state.getCurrentTurnColor());
        assertFalse(state.isDiceRolled());
    }

    @Test
    public void testTokenLeavesBaseOnlyOn6() throws Exception {
        room = roomService.startGame(roomCode);
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);
        
        Player redPlayer = room.getPlayers().stream().filter(p -> p.getColor().equals("RED")).findFirst().orElseThrow();

        // Test roll non-6 (e.g. 5) -> token cannot leave base
        state.setDiceValue(5);
        state.setDiceRolled(true);
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.saveAndFlush(room);

        // Attempt move token 0 from base
        gameService.moveToken(roomCode, redPlayer.getId(), 0);

        // State reload
        room = roomRepository.findByCode(roomCode).orElseThrow();
        state = objectMapper.readValue(room.getGameState(), GameState.class);
        assertEquals(-1, state.getPlayers().get("RED").getTokens().get(0).getPosition()); // still in base

        // Test roll 6 -> token can leave base
        state.setDiceValue(6);
        state.setDiceRolled(true);
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.saveAndFlush(room);

        gameService.moveToken(roomCode, redPlayer.getId(), 0);

        room = roomRepository.findByCode(roomCode).orElseThrow();
        state = objectMapper.readValue(room.getGameState(), GameState.class);
        assertEquals(0, state.getPlayers().get("RED").getTokens().get(0).getPosition()); // left base!
    }

    @Test
    public void testTokenCannotOvershoot57() throws Exception {
        room = roomService.startGame(roomCode);
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);
        Player redPlayer = room.getPlayers().stream().filter(p -> p.getColor().equals("RED")).findFirst().orElseThrow();

        // Put token at position 55
        state.getPlayers().get("RED").getTokens().get(0).setPosition(55);
        // Roll 4 (55 + 4 = 59 > 57)
        state.setDiceValue(4);
        state.setDiceRolled(true);
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.saveAndFlush(room);

        // Try to move
        gameService.moveToken(roomCode, redPlayer.getId(), 0);

        room = roomRepository.findByCode(roomCode).orElseThrow();
        state = objectMapper.readValue(room.getGameState(), GameState.class);
        assertEquals(55, state.getPlayers().get("RED").getTokens().get(0).getPosition()); // did not move (no overshoot!)
    }

    @Test
    public void testTokenMovesFrom51IntoHomeLaneCorrectly() throws Exception {
        room = roomService.startGame(roomCode);
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);
        Player redPlayer = room.getPlayers().stream().filter(p -> p.getColor().equals("RED")).findFirst().orElseThrow();

        // Put token at 51 (last track cell before home lane starts)
        state.getPlayers().get("RED").getTokens().get(0).setPosition(51);
        // Roll 2 (51 + 2 = 53)
        state.setDiceValue(2);
        state.setDiceRolled(true);
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.saveAndFlush(room);

        gameService.moveToken(roomCode, redPlayer.getId(), 0);

        room = roomRepository.findByCode(roomCode).orElseThrow();
        state = objectMapper.readValue(room.getGameState(), GameState.class);
        assertEquals(53, state.getPlayers().get("RED").getTokens().get(0).getPosition()); // entered home lane!
    }

    @Test
    public void testTokenAt57CannotMove() throws Exception {
        room = roomService.startGame(roomCode);
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);
        Player redPlayer = room.getPlayers().stream().filter(p -> p.getColor().equals("RED")).findFirst().orElseThrow();

        // Put token at 57 (reached home)
        state.getPlayers().get("RED").getTokens().get(0).setPosition(57);
        state.setDiceValue(1);
        state.setDiceRolled(true);
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.saveAndFlush(room);

        gameService.moveToken(roomCode, redPlayer.getId(), 0);

        room = roomRepository.findByCode(roomCode).orElseThrow();
        state = objectMapper.readValue(room.getGameState(), GameState.class);
        assertEquals(57, state.getPlayers().get("RED").getTokens().get(0).getPosition()); // remains at 57
    }

    @Test
    public void testPlayerReceivesRewardOnceAfterAll4TokensReach57() throws Exception {
        room = roomService.startGame(roomCode);
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);
        Player redPlayer = room.getPlayers().stream().filter(p -> p.getColor().equals("RED")).findFirst().orElseThrow();

        // Set coins initial to 0 for RED
        User redUser = redPlayer.getUser();
        redUser.setCoins(0);
        userRepository.saveAndFlush(redUser);

        // Put 3 tokens already at 57, 1 token at 56
        state.getPlayers().get("RED").getTokens().get(0).setPosition(57);
        state.getPlayers().get("RED").getTokens().get(1).setPosition(57);
        state.getPlayers().get("RED").getTokens().get(2).setPosition(57);
        state.getPlayers().get("RED").getTokens().get(3).setPosition(56);
        
        state.setDiceValue(1);
        state.setDiceRolled(true);
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.saveAndFlush(room);

        // Move last token to 57
        gameService.moveToken(roomCode, redPlayer.getId(), 3);

        room = roomRepository.findByCode(roomCode).orElseThrow();
        state = objectMapper.readValue(room.getGameState(), GameState.class);
        
        // Final position should be 57
        assertEquals(57, state.getPlayers().get("RED").getTokens().get(3).getPosition());
        assertTrue(state.getPlayers().get("RED").isHasFinished());

        // Red should receive 50 coins (for the last token reaching home) + 500 coins (1st place reward) = 550 coins total!
        int expectedCoins = 550;
        User updatedUser = userRepository.findById(redUser.getId()).orElseThrow();
        assertEquals(expectedCoins, updatedUser.getCoins());

        // Verify checkWinner is idempotent by trying to move a finished token again (should do nothing, no extra coins)
        gameService.moveToken(roomCode, redPlayer.getId(), 0);
        updatedUser = userRepository.findById(redUser.getId()).orElseThrow();
        assertEquals(expectedCoins, updatedUser.getCoins()); // coin balance remains exactly 550!
    }

    @Test
    public void testTurnSwitchesCorrectly() throws Exception {
        room = roomService.startGame(roomCode);
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);
        Player redPlayer = room.getPlayers().stream().filter(p -> p.getColor().equals("RED")).findFirst().orElseThrow();

        // RED turn, rolls a 3. Let's make a legal move from index 0 to 3
        state.getPlayers().get("RED").getTokens().get(0).setPosition(0);
        state.setDiceValue(3);
        state.setDiceRolled(true);
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.saveAndFlush(room);

        gameService.moveToken(roomCode, redPlayer.getId(), 0);

        room = roomRepository.findByCode(roomCode).orElseThrow();
        state = objectMapper.readValue(room.getGameState(), GameState.class);
        assertEquals("GREEN", state.getCurrentTurnColor()); // turn switched to GREEN!
    }

    @Test
    public void testRolling6GivesExtraTurn() throws Exception {
        room = roomService.startGame(roomCode);
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);
        Player redPlayer = room.getPlayers().stream().filter(p -> p.getColor().equals("RED")).findFirst().orElseThrow();

        // RED turn, token at 0. Rolls a 6. Moves token.
        state.getPlayers().get("RED").getTokens().get(0).setPosition(0);
        state.setDiceValue(6);
        state.setDiceRolled(true);
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.saveAndFlush(room);

        gameService.moveToken(roomCode, redPlayer.getId(), 0);

        room = roomRepository.findByCode(roomCode).orElseThrow();
        state = objectMapper.readValue(room.getGameState(), GameState.class);
        assertEquals("RED", state.getCurrentTurnColor()); // RED gets extra turn!
        assertFalse(state.isDiceRolled()); // can roll again!
    }

    @Test
    public void testTriple6RevokesTurn() throws Exception {
        room = roomService.startGame(roomCode);
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);
        Player redPlayer = room.getPlayers().stream().filter(p -> p.getColor().equals("RED")).findFirst().orElseThrow();

        // Dynamically mutate DICE_POOL to all 6s using reflection on the array reference
        java.lang.reflect.Field field = GameService.class.getDeclaredField("DICE_POOL");
        field.setAccessible(true);
        int[] pool = (int[]) field.get(null);
        
        // Mutate array to be all 6s
        for (int i = 0; i < pool.length; i++) {
            pool[i] = 6;
        }

        try {
            // First roll (should be 6)
            gameService.rollDice(roomCode, redPlayer.getId());
            room = roomRepository.findByCode(roomCode).orElseThrow();
            state = objectMapper.readValue(room.getGameState(), GameState.class);
            assertEquals("RED", state.getCurrentTurnColor());
            assertEquals(1, state.getConsecutiveSixes());
            assertTrue(state.isDiceRolled());
            
            // Move token 0 onto track
            gameService.moveToken(roomCode, redPlayer.getId(), 0);
            room = roomRepository.findByCode(roomCode).orElseThrow();
            state = objectMapper.readValue(room.getGameState(), GameState.class);
            assertEquals("RED", state.getCurrentTurnColor());
            assertFalse(state.isDiceRolled());

            // Second roll (should be 6)
            gameService.rollDice(roomCode, redPlayer.getId());
            room = roomRepository.findByCode(roomCode).orElseThrow();
            state = objectMapper.readValue(room.getGameState(), GameState.class);
            assertEquals("RED", state.getCurrentTurnColor());
            assertEquals(2, state.getConsecutiveSixes());
            assertTrue(state.isDiceRolled());
            
            // Move token 0 forward
            gameService.moveToken(roomCode, redPlayer.getId(), 0);
            room = roomRepository.findByCode(roomCode).orElseThrow();
            state = objectMapper.readValue(room.getGameState(), GameState.class);
            assertEquals("RED", state.getCurrentTurnColor());
            assertFalse(state.isDiceRolled());

            // Third roll (should be 6 -> TRIPLE 6 -> turn revoked to GREEN!)
            gameService.rollDice(roomCode, redPlayer.getId());
            room = roomRepository.findByCode(roomCode).orElseThrow();
            state = objectMapper.readValue(room.getGameState(), GameState.class);
            
            assertEquals("GREEN", state.getCurrentTurnColor());
            assertEquals(0, state.getConsecutiveSixes());
            assertFalse(state.isDiceRolled());
            assertTrue(state.getMessage().contains("TRIPLE 6"));
        } finally {
            // Restore original pool elements
            pool[0] = 1;
            pool[1] = 2;
            pool[2] = 3;
            pool[3] = 4;
            pool[4] = 5;
            pool[5] = 6;
            pool[6] = 6;
            pool[7] = 6;
        }
    }

    @Test
    public void testCapturesDoNotHappenedOnSafeCells() throws Exception {
        room = roomService.startGame(roomCode);
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);
        Player redPlayer = room.getPlayers().stream().filter(p -> p.getColor().equals("RED")).findFirst().orElseThrow();

        // Place RED token at relative position 0 (which is RED absolute 39 - a safe cell)
        // Place GREEN token at relative position 39 (which is GREEN absolute 39 - same absolute cell!)
        state.getPlayers().get("RED").getTokens().get(0).setPosition(0); // absolute 39
        state.getPlayers().get("GREEN").getTokens().get(0).setPosition(39); // absolute 39
        
        state.setDiceValue(1);
        state.setDiceRolled(true);
        state.setCurrentTurnColor("RED");
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.saveAndFlush(room);

        // RED moves from base onto relative 0
        state.getPlayers().get("RED").getTokens().get(0).setPosition(-1);
        state.getPlayers().get("GREEN").getTokens().get(0).setPosition(39); // absolute 39
        state.setDiceValue(6);
        state.setDiceRolled(true);
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.saveAndFlush(room);

        gameService.moveToken(roomCode, redPlayer.getId(), 0);

        room = roomRepository.findByCode(roomCode).orElseThrow();
        state = objectMapper.readValue(room.getGameState(), GameState.class);
        
        assertEquals(0, state.getPlayers().get("RED").getTokens().get(0).getPosition()); // RED on board (absolute 39)
        assertEquals(39, state.getPlayers().get("GREEN").getTokens().get(0).getPosition()); // GREEN is NOT captured! (still at 39)
    }

    @Test
    public void testBlockingWithTwoOpponentTokensWorks() throws Exception {
        room = roomService.startGame(roomCode);
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);
        Player redPlayer = room.getPlayers().stream().filter(p -> p.getColor().equals("RED")).findFirst().orElseThrow();

        // Place two GREEN tokens at GREEN relative position 10 (absolute index 10)
        state.getPlayers().get("GREEN").getTokens().get(0).setPosition(10);
        state.getPlayers().get("GREEN").getTokens().get(1).setPosition(10);

        // Place RED token at relative position 22 (so RED moving +1 would land on absolute index 10)
        state.getPlayers().get("RED").getTokens().get(0).setPosition(22); // absolute 9
        
        state.setDiceValue(1);
        state.setDiceRolled(true);
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.saveAndFlush(room);

        // Try to move RED token to absolute 10 (blocked by two GREEN tokens)
        gameService.moveToken(roomCode, redPlayer.getId(), 0);

        room = roomRepository.findByCode(roomCode).orElseThrow();
        state = objectMapper.readValue(room.getGameState(), GameState.class);
        
        // RED token should NOT have moved! (still at 22)
        assertEquals(22, state.getPlayers().get("RED").getTokens().get(0).getPosition());
    }

    @Test
    public void testInvalidPlayerCannotRollOrMove() throws Exception {
        room = roomService.startGame(roomCode);
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);

        // Try to roll with invalid player ID (9999L)
        gameService.rollDice(roomCode, 9999L);
        room = roomRepository.findByCode(roomCode).orElseThrow();
        GameState postRollState = objectMapper.readValue(room.getGameState(), GameState.class);
        
        // Confirm no change in state
        assertFalse(postRollState.isDiceRolled());

        // Try to move with invalid player ID (9999L)
        gameService.moveToken(roomCode, 9999L, 0);
        room = roomRepository.findByCode(roomCode).orElseThrow();
        GameState postMoveState = objectMapper.readValue(room.getGameState(), GameState.class);
        
        // Confirm no changes
        assertFalse(postMoveState.isDiceRolled());
    }

    @Test
    public void testNonCurrentPlayerCannotRollOrMove() throws Exception {
        room = roomService.startGame(roomCode);
        
        // Active player color is RED. Non-active is GREEN
        Player greenPlayer = room.getPlayers().stream().filter(p -> p.getColor().equals("GREEN")).findFirst().orElseThrow();

        // Try to roll dice on behalf of GREEN
        gameService.rollDice(roomCode, greenPlayer.getId());
        room = roomRepository.findByCode(roomCode).orElseThrow();
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);
        
        // Confirm GREEN roll was rejected
        assertFalse(state.isDiceRolled());
        assertEquals("RED", state.getCurrentTurnColor());

        // Setup RED roll so dice is rolled
        state.setDiceValue(3);
        state.setDiceRolled(true);
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.saveAndFlush(room);

        // Try to move GREEN token when it's RED's turn
        gameService.moveToken(roomCode, greenPlayer.getId(), 0);
        room = roomRepository.findByCode(roomCode).orElseThrow();
        state = objectMapper.readValue(room.getGameState(), GameState.class);

        // Confirm GREEN's token did not leave the base
        assertEquals(-1, state.getPlayers().get("GREEN").getTokens().get(0).getPosition());
    }

    @Test
    public void testCorruptedRoomTimerHandling() throws Exception {
        room = roomService.startGame(roomCode);
        
        // Put corrupted unparseable JSON into gameState
        room.setGameState("{{invalid json");
        roomRepository.saveAndFlush(room);

        // Run processRoomTimer (should fail to parse and trigger finishCorruptedRoom)
        gameService.processTurnTimers();

        room = roomRepository.findByCode(roomCode).orElseThrow();
        assertEquals(Room.RoomStatus.FINISHED, room.getStatus());
    }

    @Test
    public void testSingleTokenReaching57GivesRewardOnlyOnce() throws Exception {
        room = roomService.startGame(roomCode);
        GameState state = objectMapper.readValue(room.getGameState(), GameState.class);
        Player redPlayer = room.getPlayers().stream().filter(p -> p.getColor().equals("RED")).findFirst().orElseThrow();

        // Set RED coins to 0
        User redUser = redPlayer.getUser();
        redUser.setCoins(0);
        userRepository.saveAndFlush(redUser);

        // Token at position 56, rolls 1
        state.getPlayers().get("RED").getTokens().get(0).setPosition(56);
        state.setDiceValue(1);
        state.setDiceRolled(true);
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.saveAndFlush(room);

        // Move token 0 to 57
        gameService.moveToken(roomCode, redPlayer.getId(), 0);

        room = roomRepository.findByCode(roomCode).orElseThrow();
        state = objectMapper.readValue(room.getGameState(), GameState.class);
        
        // Verify token reached 57, user received 50 coins reward
        assertEquals(57, state.getPlayers().get("RED").getTokens().get(0).getPosition());
        User updatedUser = userRepository.findById(redUser.getId()).orElseThrow();
        assertEquals(50, updatedUser.getCoins());

        // Try to move again (should be illegal, should not grant another 50 coins)
        state.setDiceValue(1);
        state.setDiceRolled(true);
        room.setGameState(objectMapper.writeValueAsString(state));
        roomRepository.saveAndFlush(room);

        gameService.moveToken(roomCode, redPlayer.getId(), 0);
        
        updatedUser = userRepository.findById(redUser.getId()).orElseThrow();
        assertEquals(50, updatedUser.getCoins()); // coin balance remains exactly 50!
    }

    @Test
    public void testClassicTwoPlayerRoomStartsWithTwoColors() throws Exception {
        Room twoPlayerRoom = roomService.createRoom("TwoHost", 2, "CLASSIC");
        twoPlayerRoom = roomService.joinRoom(twoPlayerRoom.getCode(), "TwoGuest");
        twoPlayerRoom = roomService.startGame(twoPlayerRoom.getCode());

        GameState state = objectMapper.readValue(twoPlayerRoom.getGameState(), GameState.class);
        assertEquals(List.of("RED", "GREEN"), state.getPlayerColors());
        assertEquals(2, state.getPlayers().size());
        assertEquals("RED", state.getCurrentTurnColor());
    }

    @Test
    public void testClassicThreePlayerRoomStartsWithThreeColors() throws Exception {
        Room threePlayerRoom = roomService.createRoom("ThreeHost", 3, "CLASSIC");
        threePlayerRoom = roomService.joinRoom(threePlayerRoom.getCode(), "ThreeGuestA");
        threePlayerRoom = roomService.joinRoom(threePlayerRoom.getCode(), "ThreeGuestB");
        threePlayerRoom = roomService.startGame(threePlayerRoom.getCode());

        GameState state = objectMapper.readValue(threePlayerRoom.getGameState(), GameState.class);
        assertEquals(List.of("RED", "GREEN", "YELLOW"), state.getPlayerColors());
        assertEquals(3, state.getPlayers().size());
        assertEquals("RED", state.getCurrentTurnColor());
        assertTrue(state.getPlayers().containsKey("YELLOW"));
        assertFalse(state.getPlayers().containsKey("BLUE"));
    }

    @Test
    public void testClassicFourPlayerRoomStartsWithFourColors() throws Exception {
        Room fourPlayerRoom = roomService.createRoom("FourHost", 4, "CLASSIC");
        fourPlayerRoom = roomService.joinRoom(fourPlayerRoom.getCode(), "FourGuestA");
        fourPlayerRoom = roomService.joinRoom(fourPlayerRoom.getCode(), "FourGuestB");
        fourPlayerRoom = roomService.joinRoom(fourPlayerRoom.getCode(), "FourGuestC");
        fourPlayerRoom = roomService.startGame(fourPlayerRoom.getCode());

        GameState state = objectMapper.readValue(fourPlayerRoom.getGameState(), GameState.class);
        assertEquals(List.of("RED", "GREEN", "YELLOW", "BLUE"), state.getPlayerColors());
        assertEquals(4, state.getPlayers().size());
        assertEquals("RED", state.getCurrentTurnColor());
    }

    @Test
    public void testRoomRejectsJoinAfterPlayerLimit() {
        Room twoPlayerRoom = roomService.createRoom("LimitHost", 2, "CLASSIC");
        roomService.joinRoom(twoPlayerRoom.getCode(), "LimitGuest");

        RuntimeException error = assertThrows(RuntimeException.class, () ->
                roomService.joinRoom(twoPlayerRoom.getCode(), "LimitExtra")
        );
        assertTrue(error.getMessage().contains("already full"));
    }

    @Test
    public void testAlliance2v2AlwaysRequiresFourPlayers() throws Exception {
        Room allianceRoom = roomService.createRoom("AllianceHost", 2, "2V2");
        assertEquals(4, allianceRoom.getMaxPlayers());

        String allianceRoomCode = allianceRoom.getCode();
        roomService.joinRoom(allianceRoomCode, "AllianceGuestA");
        RuntimeException startError = assertThrows(RuntimeException.class, () ->
                roomService.startGame(allianceRoomCode)
        );
        assertTrue(startError.getMessage().contains("exactly 4 players"));

        roomService.joinRoom(allianceRoomCode, "AllianceGuestB");
        allianceRoom = roomService.joinRoom(allianceRoomCode, "AllianceGuestC");
        allianceRoom = roomService.startGame(allianceRoomCode);

        GameState state = objectMapper.readValue(allianceRoom.getGameState(), GameState.class);
        assertEquals(List.of("RED", "GREEN", "YELLOW", "BLUE"), state.getPlayerColors());
        assertEquals(4, state.getPlayers().size());
    }
}
