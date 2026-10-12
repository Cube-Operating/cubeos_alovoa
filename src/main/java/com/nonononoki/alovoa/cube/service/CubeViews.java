package com.nonononoki.alovoa.cube.service;

import com.nonononoki.alovoa.Tools;
import com.nonononoki.alovoa.entity.User;
import com.nonononoki.alovoa.entity.user.UserImage;
import com.nonononoki.alovoa.entity.user.UserInterest;
import com.nonononoki.alovoa.entity.user.UserVerificationPicture;
import com.nonononoki.alovoa.model.UserDto;
import com.nonononoki.alovoa.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * CubeOS: builds the JSON shapes of the Cube App API from Alovoa users. Only fields listed in the API
 * contract are ever emitted: never emails, Cubemail handles or coordinates, and Cubelines only where the
 * caller has already established a mutual match.
 */
@Component
public class CubeViews {

    @Autowired
    private UserService userService;

    @Value("${cube.photo-url-prefix}")
    private String photoUrlPrefix;

    public record Photo(String id, String url) {
    }

    public String photoUrl(UUID id) {
        return photoUrlPrefix + id;
    }

    /** All photos of a user, profile picture first, then extra images in upload order. */
    public List<Photo> photos(User user) {
        List<Photo> list = new ArrayList<>();
        if (user.getProfilePicture() != null) {
            UUID id = Tools.getProfilePictureUUID(user.getProfilePicture(), userService);
            list.add(new Photo(id.toString(), photoUrl(id)));
        }
        if (user.getImages() != null) {
            List<UserImage> images = new ArrayList<>(user.getImages());
            images.sort(Comparator.comparing(UserImage::getId, Comparator.nullsLast(Comparator.naturalOrder())));
            for (UserImage img : images) {
                UUID id = Tools.getImageUUID(img, userService);
                list.add(new Photo(id.toString(), photoUrl(id)));
            }
        }
        return list;
    }

    public List<String> photoUrls(User user) {
        return photos(user).stream().map(Photo::url).toList();
    }

    public String profilePhotoUrl(User user) {
        List<Photo> p = photos(user);
        return p.isEmpty() ? null : p.get(0).url();
    }

    public static Integer age(User user) {
        if (user.getDates() == null || user.getDates().getDateOfBirth() == null) {
            return null;
        }
        return Tools.calcUserAge(user);
    }

    public static List<String> interests(User user) {
        if (user.getInterests() == null) {
            return List.of();
        }
        return user.getInterests().stream().map(UserInterest::getText).filter(Objects::nonNull).sorted().toList();
    }

    public static boolean verified(User user) {
        UserVerificationPicture pic = user.getVerificationPicture();
        if (pic == null) {
            return false;
        }
        return pic.isVerifiedByAdmin() || (pic.getUserYes() != null && pic.getUserNo() != null && UserDto.isVerifiedByUsers(pic));
    }

    public static Long intentionId(User user) {
        return user.getIntention() == null ? null : user.getIntention().getId();
    }

    /** A user card in discovery: no Cubeline, no exact location. */
    public Map<String, Object> discoverCard(User user, int distanceKm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid", user.getUuid().toString());
        m.put("name", user.getFirstName());
        m.put("age", age(user));
        m.put("distanceKm", distanceKm);
        m.put("photos", photoUrls(user));
        m.put("description", user.getDescription());
        m.put("interests", interests(user));
        m.put("verified", verified(user));
        m.put("intention", intentionId(user));
        return m;
    }

    /** Match info: only call for a mutual match. */
    public Map<String, Object> matchInfo(User other, String cubeline) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid", other.getUuid().toString());
        m.put("name", other.getFirstName());
        m.put("photo", profilePhotoUrl(other));
        m.put("cubeline", cubeline);
        return m;
    }
}
