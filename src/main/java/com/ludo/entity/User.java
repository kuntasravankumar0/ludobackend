package com.ludo.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private long coins = 1000; // Default starting coins

    public void addCoins(long amount) {
        this.coins += amount;
    }

    public void deductCoins(long amount) {
        this.coins = Math.max(0, this.coins - amount);
    }
}
