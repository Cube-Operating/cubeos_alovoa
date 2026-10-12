package com.nonononoki.alovoa.cube.service;

import com.nonononoki.alovoa.cube.auth.CubeIdentityClient;
import com.nonononoki.alovoa.cube.entity.CubeAccount;
import com.nonononoki.alovoa.cube.repo.CubeAccountRepository;
import com.nonononoki.alovoa.entity.User;
import com.nonononoki.alovoa.entity.user.UserSettings;
import com.nonononoki.alovoa.model.UserDeleteParams;
import com.nonononoki.alovoa.repo.*;
import com.nonononoki.alovoa.service.UserService;
import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.UUID;

/**
 * CubeOS: maps a Cube App (Cubemail) identity onto an Alovoa user and keeps its Cubeline.
 */
@Service
public class CubeAccountService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CubeAccountService.class);
    /** Re-ask the registrar for a missing Cubeline at most this often. */
    static final long CUBELINE_RETRY_MS = 2 * 60 * 1000L;
    /** Re-check a known Cubeline this often (it can move to a new number only by re-registration). */
    static final long CUBELINE_REFRESH_MS = 24 * 3600 * 1000L;

    @Autowired
    private UserRepository userRepo;
    @Autowired
    private CubeAccountRepository cubeAccountRepo;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CubeIdentityClient identityClient;
    @Autowired
    private UserLikeRepository userLikeRepo;
    @Autowired
    private UserHideRepository userHideRepo;
    @Autowired
    private UserBlockRepository userBlockRepo;
    @Autowired
    private UserReportRepository userReportRepo;
    @Autowired
    private UserNotificationRepository userNotificationRepo;
    @Autowired
    private ConversationRepository conversationRepo;
    @Autowired
    private UserVerificationPictureRepository userVerificationPictureRepo;

    @Value("${cube.email-domain:cubemail.io}")
    private String emailDomain;

    public String emailForHandle(String handle) {
        return handle + "@" + emailDomain;
    }

    /**
     * Finds the Alovoa user for a Cubemail handle, creating it on first use: email
     * {@code <handle>@cubemail.io}, random password, confirmed, no captcha. The profile (name, date of
     * birth, photos...) is filled in later by onboarding.
     */
    public User provision(String handle) {
        String email = emailForHandle(handle);
        User user = userRepo.findByEmail(email);
        if (user != null) {
            ensureAccountRow(user);
            return user;
        }
        synchronized (this) {
            user = userRepo.findByEmail(email);
            if (user == null) {
                try {
                    user = userRepo.saveAndFlush(newUser(email));
                    LOGGER.info("Cube App: created dating user {}", user.getUuid());
                } catch (DataIntegrityViolationException e) {
                    user = userRepo.findByEmail(email);
                    if (user == null) {
                        throw e;
                    }
                }
            }
        }
        ensureAccountRow(user);
        return user;
    }

    private User newUser(String email) {
        User user = new User(email);
        user.setUuid(UUID.randomUUID());
        user.setPassword(passwordEncoder.encode(RandomStringUtils.random(48, 0, 0, true, true, null, new SecureRandom())));
        user.setConfirmed(true);
        user.setLanguage("en");
        user.setNumberReferred(0);
        UserSettings settings = new UserSettings(user);
        settings.setEmailLike(false);
        settings.setEmailChat(false);
        user.setUserSettings(settings);
        user.setPreferedGenders(new HashSet<>());
        // same empty collections as RegisterService.registerBase (HHH-9940)
        user.setInterests(new ArrayList<>());
        user.setImages(new ArrayList<>());
        user.setDonations(new ArrayList<>());
        user.setLikes(new ArrayList<>());
        user.setLikedBy(new ArrayList<>());
        user.setConversations(new ArrayList<>());
        user.setMessageReceived(new ArrayList<>());
        user.setMessageSent(new ArrayList<>());
        user.setNotifications(new ArrayList<>());
        user.setNotificationsFrom(new ArrayList<>());
        user.setHiddenByUsers(new ArrayList<>());
        user.setHiddenUsers(new ArrayList<>());
        user.setBlockedByUsers(new ArrayList<>());
        user.setBlockedUsers(new ArrayList<>());
        user.setReported(new ArrayList<>());
        user.setReportedByUsers(new ArrayList<>());
        user.setPrompts(new ArrayList<>());
        return user;
    }

    private CubeAccount ensureAccountRow(User user) {
        return cubeAccountRepo.findById(user.getId()).orElseGet(() -> {
            try {
                return cubeAccountRepo.saveAndFlush(new CubeAccount(user.getId()));
            } catch (DataIntegrityViolationException e) {
                return cubeAccountRepo.findById(user.getId()).orElseThrow(() -> e);
            }
        });
    }

    public CubeAccount account(User user) {
        return ensureAccountRow(user);
    }

    /**
     * Fetches the Cubeline from the registrar when it is unknown (at most every couple of minutes) or
     * due for a periodic re-check. A failed lookup keeps the stored number.
     */
    public void refreshCubelineIfDue(User user, String bearerToken) {
        CubeAccount account = ensureAccountRow(user);
        long now = System.currentTimeMillis();
        Date checked = account.getCubelineCheckedAt();
        long age = checked == null ? Long.MAX_VALUE : now - checked.getTime();
        boolean due = account.getCubeline() == null ? age >= CUBELINE_RETRY_MS : age >= CUBELINE_REFRESH_MS;
        if (!due) {
            return;
        }
        refreshCubeline(account, bearerToken);
    }

    public CubeAccount refreshCubeline(CubeAccount account, String bearerToken) {
        identityClient.cubelineForToken(bearerToken).ifPresent(account::setCubeline);
        account.setCubelineCheckedAt(new Date());
        return cubeAccountRepo.saveAndFlush(account);
    }

    /**
     * Deletes the user's dating profile and all Alovoa data that references it (Alovoa's own account
     * deletion cascade, minus the e-mail token/captcha confirmation: the Cube App bearer is the proof).
     * Runs in one transaction so it also works outside a web request's persistence context.
     */
    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            cubeAccountRepo.findById(userId).ifPresent(cubeAccountRepo::delete);
            return;
        }
        UserDeleteParams params = UserDeleteParams.builder().conversationRepo(conversationRepo)
                .userBlockRepo(userBlockRepo).userHideRepo(userHideRepo).userLikeRepo(userLikeRepo)
                .userNotificationRepo(userNotificationRepo).userRepo(userRepo).userReportRepo(userReportRepo)
                .userVerificationPictureRepo(userVerificationPictureRepo).build();
        UserService.removeUserDataCascading(user, params);
        Long id = user.getId();
        user = userRepo.saveAndFlush(user);
        userRepo.delete(user);
        userRepo.flush();
        cubeAccountRepo.findById(id).ifPresent(cubeAccountRepo::delete);
        cubeAccountRepo.flush();
        LOGGER.info("Cube App: deleted dating user {}", user.getUuid());
    }
}