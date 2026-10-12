package com.nonononoki.alovoa.cube.service;

import com.nonononoki.alovoa.Tools;
import com.nonononoki.alovoa.cube.web.CubeApiException;
import com.nonononoki.alovoa.entity.User;
import com.nonononoki.alovoa.entity.user.UserHide;
import com.nonononoki.alovoa.entity.user.UserLike;
import com.nonononoki.alovoa.model.AlovoaException;
import com.nonononoki.alovoa.repo.UserHideRepository;
import com.nonononoki.alovoa.repo.UserLikeRepository;
import com.nonononoki.alovoa.repo.UserRepository;
import com.nonononoki.alovoa.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * CubeOS likes, passes and matches. A match is Alovoa's mutual like (UserLike both ways). Unlike
 * Alovoa's UserService.likeUser this creates no Alovoa conversation and sends no e-mail: Cube App
 * users chat over Session, opened by the other person's Cubeline, which is only ever returned here and
 * only for a mutual, unblocked match.
 */
@Service
public class CubeMatchService {

    @Autowired
    private CubeAccess access;
    @Autowired
    private CubeViews views;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private UserLikeRepository userLikeRepo;
    @Autowired
    private UserHideRepository userHideRepo;

    @Value("${app.search.ignore-intention}")
    private boolean ignoreIntention;

    public Map<String, Object> like(String uuid) {
        User me = access.currentExploringUser();
        User other = access.visibleOther(me, uuid);
        if (!access.isVisible(other)) {
            throw CubeApiException.profileGone(); // hidden profiles can't be liked
        }
        if (!Tools.usersCompatible(me, other, ignoreIntention)) {
            throw CubeApiException.badRequest("You can't like this profile.");
        }
        if (userLikeRepo.findByUserFromAndUserTo(me, other) == null) {
            UserLike like = new UserLike();
            like.setDate(new Date());
            like.setUserFrom(me);
            like.setUserTo(other);
            me.getLikes().add(like);
            // as in UserService.likeUser: liking someone un-hides them
            me.getHiddenUsers().removeIf(h -> h.getUserTo() != null && h.getUserTo().getId().equals(other.getId()));
            userRepo.saveAndFlush(me);
        }
        boolean match = userLikeRepo.findByUserFromAndUserTo(other, me) != null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("match", match);
        if (match) {
            m.put("match_info", views.matchInfo(other, access.cubeline(other).orElse(null)));
        }
        return m;
    }

    /**
     * Pass: hides the person from the caller's own deck only (an Alovoa UserHide from the caller). Allowed
     * while browsing privately. Unlike UserService.hideUser it never auto-blocks the other person, so a
     * pass leaves no trace on their side.
     */
    public void pass(String uuid) {
        User me = access.currentOnboardedUser();
        User other = access.visibleOther(me, uuid);
        hide(me, other);
        userRepo.saveAndFlush(me);
    }

    public Map<String, Object> matches() {
        User me = access.currentOnboardedUser();
        record Row(Date at, Map<String, Object> view) {
        }
        List<Row> rows = new ArrayList<>();
        for (UserLike mine : userLikeRepo.findByUserFrom(me)) {
            User other = mine.getUserTo();
            if (other == null) {
                continue;
            }
            UserLike theirs = userLikeRepo.findByUserFromAndUserTo(other, me);
            if (theirs == null || access.blockedEitherWay(me, other) || !access.isActiveCubeProfile(other)) {
                continue;
            }
            Date at = later(mine.getDate(), theirs.getDate());
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("uuid", other.getUuid().toString());
            v.put("name", other.getFirstName());
            v.put("age", CubeViews.age(other));
            v.put("photo", views.profilePhotoUrl(other));
            v.put("cubeline", access.cubeline(other).orElse(null));
            v.put("matchedAt", at == null ? null : at.toInstant().toString());
            rows.add(new Row(at, v));
        }
        rows.sort(Comparator.comparing(Row::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return Map.of("matches", rows.stream().map(Row::view).toList());
    }

    /** Unmatch: removes the likes in both directions and hides each from the other. */
    public void unmatch(String uuid) {
        User me = access.currentOnboardedUser();
        User other = existingOther(me, uuid);
        UserLike mine = userLikeRepo.findByUserFromAndUserTo(me, other);
        UserLike theirs = userLikeRepo.findByUserFromAndUserTo(other, me);
        if (mine == null || theirs == null) {
            throw CubeApiException.notFound("You're not matched with this person.");
        }
        me.getLikes().removeIf(l -> l.getUserTo() != null && l.getUserTo().getId().equals(other.getId()));
        other.getLikes().removeIf(l -> l.getUserTo() != null && l.getUserTo().getId().equals(me.getId()));
        hide(me, other);
        hide(other, me);
        userRepo.saveAndFlush(me);
        userRepo.saveAndFlush(other);
    }

    public void block(String uuid) throws AlovoaException {
        User me = access.currentOnboardedUser();
        User other = existingOther(me, uuid);
        userService.blockUser(other.getUuid());
    }

    public void report(String uuid, String reason) throws AlovoaException {
        String text = reason == null ? "" : reason.strip();
        if (text.isEmpty()) {
            throw CubeApiException.badRequest("Please tell us what happened.");
        }
        if (text.length() > 255) {
            throw CubeApiException.badRequest("Please keep the report under 255 characters.");
        }
        User me = access.currentOnboardedUser();
        User other = existingOther(me, uuid);
        userService.reportUser(other.getUuid(), text);
        if (userHideRepo.findByUserFromAndUserTo(me, other) == null) {
            userService.hideUser(other.getUuid());
        }
    }

    private void hide(User from, User to) {
        if (userHideRepo.findByUserFromAndUserTo(from, to) == null) {
            UserHide hide = new UserHide();
            hide.setDate(new Date());
            hide.setUserFrom(from);
            hide.setUserTo(to);
            from.getHiddenUsers().add(hide);
        }
    }

    /** Any other existing user (blocking/reporting/unmatching must work even on hidden profiles). */
    private User existingOther(User me, String uuid) {
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw CubeApiException.profileGone();
        }
        User other = userRepo.findByUuid(id);
        if (other == null || other.getId().equals(me.getId()) || other.isAdmin()) {
            throw CubeApiException.profileGone();
        }
        return other;
    }

    private static Date later(Date a, Date b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.after(b) ? a : b;
    }
}
