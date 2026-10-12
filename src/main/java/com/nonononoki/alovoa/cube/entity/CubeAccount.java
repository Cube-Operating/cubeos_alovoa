package com.nonononoki.alovoa.cube.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

/**
 * CubeOS side table for Cube App users, keyed by the Alovoa user id. Holds the user's Cubeline
 * (shown only to mutual matches) and when the coarse location was last updated (stale locations fall
 * out of discovery). The Cubemail handle itself is not stored here: it only lives in the user's
 * (encrypted) Alovoa email {@code <handle>@cubemail.io}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cube_account")
public class CubeAccount {

    @Id
    private Long userId;

    @Column(length = 11)
    private String cubeline;

    private Date cubelineCheckedAt;

    private Date locationUpdatedAt;

    private Date createdAt;

    /**
     * Opt-in ("See and be seen"): only visible users appear in other people's discovery and may like.
     * Hidden users can still browse privately and pass. False for every new account.
     */
    @Column(nullable = false, columnDefinition = "BIT(1) NOT NULL DEFAULT 0")
    private boolean visible = false;

    public CubeAccount(Long userId) {
        this.userId = userId;
        this.createdAt = new Date();
    }
}
