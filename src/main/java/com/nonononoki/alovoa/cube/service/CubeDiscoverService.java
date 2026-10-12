package com.nonononoki.alovoa.cube.service;

import com.nonononoki.alovoa.cube.entity.CubeAccount;
import com.nonononoki.alovoa.cube.repo.CubeAccountRepository;
import com.nonononoki.alovoa.cube.web.CubeApiException;
import com.nonononoki.alovoa.entity.User;
import com.nonononoki.alovoa.model.SearchDto;
import com.nonononoki.alovoa.model.UserDto;
import com.nonononoki.alovoa.repo.UserRepository;
import com.nonononoki.alovoa.service.SearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CubeOS discovery: Alovoa's own distance search (SearchService.searchComplete, which already applies
 * onboarding/confirmation, location present, mutual gender and age preferences, likes, passes (hides)
 * and blocks in both directions), narrowed to visible (opted-in) Cube App profiles with a Cubeline and a
 * fresh location,
 * cut to a true radius and returned with whole-km distances.
 */
@Service
public class CubeDiscoverService {

    public static final int MIN_DISTANCE = 1;
    public static final int MAX_DISTANCE = 200;
    public static final int DEFAULT_DISTANCE = 50;
    public static final int MAX_LIMIT = 30;
    public static final int DEFAULT_LIMIT = 20;

    @Autowired
    private CubeAccess access;
    @Autowired
    private CubeAccountService accountService;
    @Autowired
    private CubeAccountRepository cubeAccountRepo;
    @Autowired
    private CubeViews views;
    @Autowired
    private SearchService searchService;
    @Autowired
    private UserRepository userRepo;

    public Map<String, Object> discover(Integer distanceKm, Integer limit) throws Exception {
        int distance = clamp(distanceKm == null ? DEFAULT_DISTANCE : distanceKm, MIN_DISTANCE, MAX_DISTANCE);
        int max = clamp(limit == null ? DEFAULT_LIMIT : limit, 1, MAX_LIMIT);

        // Hidden users may browse privately: they see results but never appear to others (filtered below).
        User me = access.currentOnboardedUser();
        CubeAccount myAccount = accountService.account(me);
        if (me.getLocationLatitude() == null || me.getLocationLongitude() == null || !access.isLocationFresh(myAccount)) {
            throw CubeApiException.badRequest("Share your location to discover people nearby.");
        }
        double lat = me.getLocationLatitude();
        double lon = me.getLocationLongitude();

        SearchDto result = searchService.searchComplete(SearchService.SearchParams.builder()
                .latitude(lat).longitude(lon).distance(distance)
                .showOutsideParameters(false).sort(SearchService.SORT_DISTANCE).build());
        List<UUID> uuids = result == null || result.getUsers() == null ? List.of()
                : result.getUsers().stream().map(UserDto::getUuid).filter(Objects::nonNull).toList();
        if (uuids.isEmpty()) {
            return Map.of("users", List.of());
        }

        Map<UUID, User> users = userRepo.findByUuidIn(uuids).stream()
                .collect(Collectors.toMap(User::getUuid, Function.identity(), (a, b) -> a));
        Map<Long, CubeAccount> accounts = cubeAccountRepo.findAllById(users.values().stream().map(User::getId).toList())
                .stream().collect(Collectors.toMap(CubeAccount::getUserId, Function.identity()));

        record Candidate(User user, double km) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (UUID uuid : uuids) {
            User u = users.get(uuid);
            if (u == null || u.getId().equals(me.getId()) || u.isDisabled() || !CubeAccess.isOnboarded(u)) {
                continue;
            }
            CubeAccount account = accounts.get(u.getId());
            if (account == null || !account.isVisible() || account.getCubeline() == null || !access.isLocationFresh(account)) {
                continue; // not a Cube App profile, hidden (opt-in), no way to chat, or stale location
            }
            if (u.getLocationLatitude() == null || u.getLocationLongitude() == null) {
                continue;
            }
            double km = CubeGeo.distanceKm(lat, lon, u.getLocationLatitude(), u.getLocationLongitude());
            if (km > distance) {
                continue; // the SQL search is a bounding box; keep it a circle
            }
            candidates.add(new Candidate(u, km));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::km));
        List<Map<String, Object>> cards = candidates.stream().limit(max)
                .map(c -> views.discoverCard(c.user(), CubeGeo.displayKm(c.km()))).toList();
        return Map.of("users", cards);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
