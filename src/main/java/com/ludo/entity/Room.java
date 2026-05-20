package com.ludo.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "rooms")
public class Room {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String code;

    private int maxPlayers;

    @Enumerated(EnumType.STRING)
    private RoomStatus status; // WAITING, PLAYING, FINISHED

    private String gameMode;

    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id ASC")
    private List<Player> players = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String gameState; // Store JSON string of game state

    private long createdAt = System.currentTimeMillis();

    public enum RoomStatus {
        WAITING, PLAYING, FINISHED
    }
}
