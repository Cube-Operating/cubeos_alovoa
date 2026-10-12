package com.nonononoki.alovoa.cube.service;

import com.nonononoki.alovoa.cube.entity.CubeAccount;
import com.nonononoki.alovoa.cube.repo.CubeAccountRepository;
import com.nonononoki.alovoa.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CubeOS: clears locations older than cube.location.max-age-days so the users drop out of Alovoa's
 * search (which requires a location). Discovery also filters stale locations at query time.
 */
@Component
@ConditionalOnProperty("app.scheduling.enabled")
public class CubeLocationJanitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CubeLocationJanitor.class);

    @Autowired
    private CubeAccountRepository cubeAccountRepo;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private CubeAccess access;

    @Scheduled(fixedDelayString = "${app.schedule.long}", initialDelay = 60_000)
    public void clearStaleLocations() {
        int cleared = 0;
        for (CubeAccount account : cubeAccountRepo.findByLocationUpdatedAtBefore(access.staleBefore())) {
            userRepo.findById(account.getUserId()).ifPresent(u -> {
                u.setLocationLatitude(null);
                u.setLocationLongitude(null);
                userRepo.save(u);
            });
            account.setLocationUpdatedAt(null);
            cubeAccountRepo.save(account);
            cleared++;
        }
        if (cleared > 0) {
            userRepo.flush();
            cubeAccountRepo.flush();
            LOGGER.info("Cube App: cleared {} stale locations", cleared);
        }
    }
}
