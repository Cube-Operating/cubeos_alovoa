package com.nonononoki.alovoa.cube.service;

import com.nonononoki.alovoa.cube.entity.CubeAccount;
import com.nonononoki.alovoa.cube.repo.CubeAccountRepository;
import com.nonononoki.alovoa.cube.web.CubeApiException;
import com.nonononoki.alovoa.entity.User;
import com.nonononoki.alovoa.model.AlovoaException;
import com.nonononoki.alovoa.repo.UserBlockRepository;
import com.nonononoki.alovoa.repo.UserLikeRepository;
import com.nonononoki.alovoa.repo.UserRepository;
import com.nonononoki.alovoa.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * CubeOS: who may see whom. Shared rules for discovery, likes, photos and matches.
 */
@Component
public class CubeAccess {

    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private UserBlockRepository userBlockRepo;
    @Autowired
    private UserLikeRepository userLikeRepo;
    @Autowired
    private CubeAccountRepository cubeAccountRepo;

    @Value("${cube.location.max-age-days:30}")
    private int locationMaxAgeDays;

    /** The signed-in Cube App user (managed by the current request's persistence context). */
    public User currentUser() {
        try {
            return authService.getCurrentUser(true);
        } catch (AlovoaException e) {
            throw new CubeApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Sign in to Cube App to use dating.");
        }
    }

    public User currentOnboardedUser() {
        User user = currentUser();
        if (!isOnboarded(user)) {
            throw CubeApiException.notOnboarded();
        }
        return user;
    }

    /** Onboarded = has a name, date of birth, gender, intention and a profile picture. */
    public static boolean isOnboarded(User user) {
        return user.getDates() != null && user.getDates().getDateOfBirth() != null && user.getFirstName() != null
                && user.getGender() != null && user.getIntention() != null && user.getProfilePicture() != null;
    }

    /** The signed-in user, onboarded and visible ("See and be seen"); else 403 not_onboarded / not_visible. */
    public User currentExploringUser() {
        User user = currentOnboardedUser();
        if (!cubeAccountRepo.findById(user.getId()).map(CubeAccount::isVisible).orElse(false)) {
            throw CubeApiException.notVisible();
        }
        return user;
    }

    public boolean isVisible(User user) {
        return cubeAccountRepo.findById(user.getId()).map(CubeAccount::isVisible).orElse(false);
    }

    public boolean isLocationFresh(CubeAccount account) {
        return account != null && account.getLocationUpdatedAt() != null
                && account.getLocationUpdatedAt().after(staleBefore());
    }

    public Date staleBefore() {
        return new Date(System.currentTimeMillis() - locationMaxAgeDays * 24L * 3600 * 1000);
    }

    public boolean blockedEitherWay(User a, User b) {
        return userBlockRepo.findByUserFromAndUserTo(a, b) != null || userBlockRepo.findByUserFromAndUserTo(b, a) != null;
    }

    public boolean isMutualMatch(User a, User b) {
        return userLikeRepo.findByUserFromAndUserTo(a, b) != null && userLikeRepo.findByUserFromAndUserTo(b, a) != null
                && !blockedEitherWay(a, b);
    }

    /** An active Cube App dating profile (other users may interact with it). */
    public boolean isActiveCubeProfile(User user) {
        return user != null && !user.isDisabled() && !user.isAdmin() && isOnboarded(user)
                && cubeAccountRepo.existsById(user.getId());
    }

    /**
     * Resolves another user that the viewer may interact with: an active Cube profile, not the viewer,
     * no block in either direction. Anything else looks like "not found".
     */
    public User visibleOther(User viewer, String uuid) {
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw CubeApiException.profileGone();
        }
        User other = userRepo.findByUuid(id);
        if (other == null || other.getId().equals(viewer.getId())) {
            throw CubeApiException.profileGone();
        }
        if (!isActiveCubeProfile(other) || blockedEitherWay(viewer, other)) {
            throw CubeApiException.profileGone();
        }
        return other;
    }

    public Optional<String> cubeline(User user) {
        return cubeAccountRepo.findById(user.getId()).map(CubeAccount::getCubeline);
    }
}
