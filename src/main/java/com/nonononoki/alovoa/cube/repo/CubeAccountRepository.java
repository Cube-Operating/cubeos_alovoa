package com.nonononoki.alovoa.cube.repo;

import com.nonononoki.alovoa.cube.entity.CubeAccount;
import com.nonononoki.alovoa.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public interface CubeAccountRepository extends JpaRepository<CubeAccount, Long> {

    List<CubeAccount> findByLocationUpdatedAtBefore(Date date);

    /** Owner of a profile picture (UserProfilePicture.user is not always set by Alovoa). */
    @Query("SELECT u FROM User u WHERE u.profilePicture.uuid = :uuid")
    User findUserByProfilePictureUuid(@Param("uuid") UUID uuid);
}
