package com.nonononoki.alovoa.cube.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.urls.detection.UrlDetector;
import com.linkedin.urls.detection.UrlDetectorOptions;
import com.nonononoki.alovoa.Tools;
import com.nonononoki.alovoa.component.TextEncryptorConverter;
import com.nonononoki.alovoa.cube.entity.CubeAccount;
import com.nonononoki.alovoa.cube.repo.CubeAccountRepository;
import com.nonononoki.alovoa.cube.web.CubeApiException;
import com.nonononoki.alovoa.entity.User;
import com.nonononoki.alovoa.entity.user.*;
import com.nonononoki.alovoa.model.AlovoaException;
import com.nonononoki.alovoa.model.ProfileOnboardingDto;
import com.nonononoki.alovoa.repo.GenderRepository;
import com.nonononoki.alovoa.repo.UserImageRepository;
import com.nonononoki.alovoa.repo.UserIntentionRepository;
import com.nonononoki.alovoa.repo.UserRepository;
import com.nonononoki.alovoa.service.UserService;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * CubeOS: the signed-in user's own dating profile (onboarding, edits, photos, location). Persists through
 * Alovoa's entities and, where they fit, Alovoa's UserService methods.
 */
@Service
public class CubeProfileService {

    /** 18+ only, whatever app.age.min says. */
    public static final int LEGAL_MIN_AGE = Tools.AGE_LEGAL;
    public static final int MAX_PHOTOS = 6;
    private static final Pattern INTEREST = Pattern.compile("[a-zA-Z0-9-]+");

    private static final List<String> INTEREST_SUGGESTIONS = List.of("music", "travel", "fitness", "movies",
            "cooking", "reading", "gaming", "art", "photography", "hiking", "dancing", "football", "fashion",
            "tech", "coffee", "pets", "yoga", "foodie", "nature", "writing", "business", "church", "comedy",
            "basketball", "afrobeats");
    private static final Map<String, String> GENDER_NAMES = Map.of("male", "Man", "female", "Woman", "other", "Other");
    private static final Map<String, String> INTENTION_NAMES = Map.of("meet", "Meet new people", "date", "Dating", "sex", "Something casual");

    @Autowired
    private CubeAccess access;
    @Autowired
    private CubeViews views;
    @Autowired
    private CubeAccountService accountService;
    @Autowired
    private CubeAccountRepository cubeAccountRepo;
    @Autowired
    private CubeImageNormalizer imageNormalizer;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private UserImageRepository userImageRepo;
    @Autowired
    private GenderRepository genderRepo;
    @Autowired
    private UserIntentionRepository intentionRepo;
    @Autowired
    private TextEncryptorConverter textEncryptor;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.age.min}")
    private int appMinAge;
    @Value("${app.age.max}")
    private int appMaxAge;
    @Value("${app.age.range}")
    private int ageRange;
    @Value("${app.first-name.length-min}")
    private int nameMin;
    @Value("${app.first-name.length-max}")
    private int nameMax;
    @Value("${app.profile.description.size}")
    private int descriptionSize;
    @Value("${app.interest.max}")
    private int interestMax;
    @Value("${app.interest.min-chars}")
    private int interestMinChars;
    @Value("${app.interest.max-chars}")
    private int interestMaxChars;
    @Value("${app.intention.delay}")
    private long intentionDelay;

    /** Profile fields accepted by POST /onboarding (data part) and PUT /profile. All optional for PUT. */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProfileInput {
        private String name;
        private String dob;
        private Long gender;
        private List<Long> preferredGenders;
        private Long intention;
        private String description;
        private List<String> interests;
        private Integer minAge;
        private Integer maxAge;
        /** Opt-in: true = "See and be seen", false = "Browse privately" (or Off, decided by the app). */
        private Boolean visible;

        boolean hasProfileFields() {
            return name != null || dob != null || gender != null || preferredGenders != null || intention != null
                    || description != null || interests != null || minAge != null || maxAge != null;
        }
    }

    public int minAge() {
        return Math.max(LEGAL_MIN_AGE, appMinAge);
    }

    // ---------------------------------------------------------------------------------------------- read

    public Map<String, Object> me() {
        User user = access.currentUser();
        CubeAccount account = accountService.account(user);
        boolean onboarded = CubeAccess.isOnboarded(user);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", user.getFirstName());
        profile.put("age", CubeViews.age(user));
        // Own profile only (for editing): the birthday itself is never shown to other people.
        profile.put("dob", user.getDates() == null || user.getDates().getDateOfBirth() == null ? null
                : Tools.dateToLocalDate(user.getDates().getDateOfBirth()).toString());
        profile.put("gender", user.getGender() == null ? null : user.getGender().getId());
        profile.put("preferredGenders", user.getPreferedGenders() == null ? List.of()
                : user.getPreferedGenders().stream().map(Gender::getId).sorted().toList());
        profile.put("intention", CubeViews.intentionId(user));
        profile.put("minAge", onboarded ? user.getPreferedMinAge() : null);
        profile.put("maxAge", onboarded ? user.getPreferedMaxAge() : null);
        profile.put("description", user.getDescription());
        profile.put("interests", CubeViews.interests(user));
        profile.put("photos", views.photos(user));
        profile.put("verified", CubeViews.verified(user));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("onboarded", onboarded);
        m.put("visible", account.isVisible());
        m.put("cubeline", account.getCubeline());
        m.put("profile", profile);
        return m;
    }

    public Map<String, Object> options() {
        List<Map<String, Object>> genders = genderRepo.findAll().stream()
                .sorted(Comparator.comparing(Gender::getId))
                .map(g -> idName(g.getId(), GENDER_NAMES.getOrDefault(g.getText(), capitalize(g.getText())))).toList();
        List<Map<String, Object>> intentions = intentionRepo.findAll().stream()
                .sorted(Comparator.comparing(UserIntention::getId))
                .map(i -> idName(i.getId(), INTENTION_NAMES.getOrDefault(i.getText(), capitalize(i.getText())))).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("genders", genders);
        m.put("intentions", intentions);
        m.put("interestsSuggestions", INTEREST_SUGGESTIONS);
        return m;
    }

    private static Map<String, Object> idName(Long id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------------------------------------------------------------------------------- onboarding

    public ProfileInput parseInput(String json) {
        if (json == null || json.isBlank()) {
            throw CubeApiException.badRequest("Profile details are missing.");
        }
        try {
            ProfileInput in = objectMapper.readValue(json, ProfileInput.class);
            if (in == null) {
                throw CubeApiException.badRequest("Profile details are missing.");
            }
            return in;
        } catch (IOException e) {
            throw CubeApiException.badRequest("Profile details are not valid.");
        }
    }

    public Map<String, Object> onboard(MultipartFile photo, ProfileInput in, String bearer) throws AlovoaException, IOException {
        User user = access.currentUser();
        if (CubeAccess.isOnboarded(user)) {
            throw CubeApiException.conflict("Your dating profile is already set up.");
        }

        // validate everything before writing anything
        String name = validName(in.getName());
        Date dob = validDob(in.getDob());
        Gender gender = validGender(in.getGender());
        Set<Gender> preferred = validPreferredGenders(in.getPreferredGenders());
        UserIntention intention = validIntention(in.getIntention());
        String description = validDescription(in.getDescription());
        List<String> interests = validInterests(in.getInterests());
        CubeImageNormalizer.Normalized image = imageNormalizer.normalize(photo);

        CubeAccount account = accountService.account(user);
        if (account.getCubeline() == null && bearer != null) {
            account = accountService.refreshCubeline(account, bearer);
        }
        if (account.getCubeline() == null) {
            throw CubeApiException.conflict("Your Cubeline isn't set up yet. Get your Cubeline in Cube App, then try again.");
        }

        writeFirstName(user, name);

        UserDates dates = user.getDates() != null ? user.getDates() : new UserDates();
        Date now = new Date();
        dates.setDateOfBirth(dob);
        dates.setActiveDate(now);
        dates.setCreationDate(now);
        dates.setIntentionChangeDate(new Date(now.getTime() - intentionDelay));
        dates.setMessageCheckedDate(now);
        dates.setMessageDate(now);
        dates.setNotificationCheckedDate(now);
        dates.setNotificationDate(now);
        dates.setUser(user);
        user.setDates(dates);
        user.setGender(gender);

        // default age bracket like RegisterService.registerBase, never below 18
        int age = Tools.calcUserAge(dob);
        int prefMin = Math.max(minAge(), age - ageRange);
        int prefMax = Math.min(appMaxAge, age + ageRange);
        user.setPreferedMinAge(dob, prefMin);
        user.setPreferedMaxAge(dob, prefMax);
        // Alovoa's onboarding only runs on a profile without picture/description
        user.setProfilePicture(null);
        user.setDescription(null);
        user.getInterests().clear();
        userRepo.saveAndFlush(user);

        ProfileOnboardingDto dto = new ProfileOnboardingDto();
        dto.setIntention(intention.getId());
        dto.setPreferredGenders(preferred.stream().map(Gender::getId).toList());
        dto.setProfilePictureMime(image.mime());
        dto.setDescription(description);
        dto.setInterests(new LinkedHashSet<>(interests));
        dto.setNotificationLike(false);
        dto.setNotificationChat(false);
        User saved = userService.onboarding(image.bytes(), dto);
        if (saved == null) {
            throw new CubeApiException(HttpStatus.CONFLICT, "Your dating profile is already set up.");
        }
        Tools.getProfilePictureUUID(saved.getProfilePicture(), userService);
        return me();
    }

    // -------------------------------------------------------------------------------------------- update

    /**
     * Turns discovery visibility on or off. Turning it on needs a finished profile (409 not_onboarded);
     * turning it off always works.
     */
    public Map<String, Object> setVisibility(Boolean visible) {
        if (visible == null) {
            throw CubeApiException.badRequest("Say whether to show you in Explore.");
        }
        User user = access.currentUser();
        if (visible && !CubeAccess.isOnboarded(user)) {
            throw CubeApiException.visibilityNeedsProfile();
        }
        CubeAccount account = accountService.account(user);
        if (account.isVisible() != visible) {
            account.setVisible(visible);
            cubeAccountRepo.saveAndFlush(account);
        }
        return me();
    }

    public Map<String, Object> update(ProfileInput in) throws AlovoaException {
        if (in.getVisible() != null && !in.hasProfileFields()) {
            return setVisibility(in.getVisible());
        }
        User user = access.currentOnboardedUser();
        if (in.getDob() != null) {
            // Editable from Cube App's dating profile; validDob still enforces the minimum age.
            Date dob = validDob(in.getDob());
            if (!Tools.dateToLocalDate(dob).equals(Tools.dateToLocalDate(user.getDates().getDateOfBirth()))) {
                user.getDates().setDateOfBirth(dob);
            }
        }
        if (in.getName() != null) {
            String name = validName(in.getName());
            if (!name.equals(user.getFirstName())) {
                writeFirstName(user, name);
            }
        }
        if (in.getGender() != null) {
            user.setGender(validGender(in.getGender()));
        }
        if (in.getPreferredGenders() != null) {
            user.setPreferedGenders(validPreferredGenders(in.getPreferredGenders()));
        }
        if (in.getDescription() != null) {
            user.setDescription(validDescription(in.getDescription()));
        }
        if (in.getInterests() != null) {
            List<String> wanted = validInterests(in.getInterests());
            user.getInterests().removeIf(i -> !wanted.contains(i.getText()));
            Set<String> have = new HashSet<>(CubeViews.interests(user));
            for (String text : wanted) {
                if (!have.contains(text)) {
                    UserInterest interest = new UserInterest();
                    interest.setText(text);
                    interest.setUser(user);
                    user.getInterests().add(interest);
                }
            }
        }
        if (in.getMinAge() != null || in.getMaxAge() != null) {
            int min = in.getMinAge() != null ? in.getMinAge() : user.getPreferedMinAge();
            int max = in.getMaxAge() != null ? in.getMaxAge() : user.getPreferedMaxAge();
            if (min < minAge() || max > appMaxAge || min > max) {
                throw CubeApiException.badRequest("Age range must be between " + minAge() + " and " + appMaxAge + ".");
            }
            user.setPreferedMinAge(min);
            user.setPreferedMaxAge(max);
        }
        userRepo.saveAndFlush(user);
        if (in.getIntention() != null && !in.getIntention().equals(CubeViews.intentionId(user))) {
            validIntention(in.getIntention());
            userService.updateIntention(in.getIntention()); // Alovoa's once-a-day cooldown applies
        }
        if (in.getVisible() != null) {
            return setVisibility(in.getVisible());
        }
        return me();
    }

    // -------------------------------------------------------------------------------------------- photos

    public CubeViews.Photo addPhoto(MultipartFile photo) throws AlovoaException, IOException {
        User user = access.currentOnboardedUser();
        if (views.photos(user).size() >= MAX_PHOTOS) {
            throw CubeApiException.badRequest("You can have up to " + MAX_PHOTOS + " photos.");
        }
        CubeImageNormalizer.Normalized image = imageNormalizer.normalize(photo);
        Set<Long> before = new HashSet<>(user.getImages().stream().map(UserImage::getId).toList());
        userService.addImage(image.bytes(), image.mime());
        User reloaded = access.currentUser();
        UserImage added = reloaded.getImages().stream().filter(i -> !before.contains(i.getId()))
                .max(Comparator.comparing(UserImage::getId))
                .orElseThrow(() -> new CubeApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Your photo couldn't be saved."));
        UUID id = Tools.getImageUUID(added, userService);
        return new CubeViews.Photo(id.toString(), views.photoUrl(id));
    }

    /**
     * Deletes a photo. Deleting the profile picture promotes the next photo; the last photo can't be
     * deleted (a profile needs one).
     */
    public void deletePhoto(String photoId) throws AlovoaException {
        User user = access.currentOnboardedUser();
        UUID id = parsePhotoId(photoId);
        UserProfilePicture pic = user.getProfilePicture();
        if (pic != null && id.equals(pic.getUuid())) {
            List<UserImage> images = new ArrayList<>(user.getImages());
            if (images.isEmpty()) {
                throw CubeApiException.badRequest("You need at least one photo.");
            }
            images.sort(Comparator.comparing(UserImage::getId));
            UserImage next = images.get(0);
            byte[] bytes = next.getBin();
            String mime = next.getBinMime();
            UUID nextUuid = next.getUuid();
            user.getImages().remove(next);
            userRepo.saveAndFlush(user);
            pic.setBin(bytes);
            pic.setBinMime(mime);
            pic.setData(null);
            pic.setUuid(nextUuid != null ? nextUuid : UUID.randomUUID());
            user.setVerificationPicture(null); // Alovoa invalidates verification when the profile picture changes
            userRepo.saveAndFlush(user);
            return;
        }
        UserImage img = user.getImages().stream().filter(i -> id.equals(i.getUuid())).findFirst()
                .orElseThrow(() -> CubeApiException.notFound("Photo not found."));
        userService.deleteImage(img.getId());
    }

    private static UUID parsePhotoId(String photoId) {
        try {
            return UUID.fromString(photoId);
        } catch (IllegalArgumentException e) {
            throw CubeApiException.notFound("Photo not found.");
        }
    }

    // ------------------------------------------------------------------------------------------ location

    public void updateLocation(Double lat, Double lon) {
        if (!CubeGeo.validLatitude(lat) || !CubeGeo.validLongitude(lon)) {
            throw CubeApiException.badRequest("Location is not valid.");
        }
        User user = access.currentUser();
        user.setLocationLatitude(CubeGeo.round(lat));
        user.setLocationLongitude(CubeGeo.round(lon));
        userRepo.saveAndFlush(user);
        CubeAccount account = accountService.account(user);
        account.setLocationUpdatedAt(new Date());
        cubeAccountRepo.saveAndFlush(account);
    }

    // -------------------------------------------------------------------------------------- validation

    /**
     * Alovoa maps firstName with updatable=false (names are fixed at registration). Cube App users get
     * their name at onboarding and may edit it, so it is written directly (encrypted like the entity).
     */
    private void writeFirstName(User user, String name) {
        try {
            jdbcTemplate.update("UPDATE `user` SET first_name = ? WHERE id = ?", textEncryptor.encode(name), user.getId());
        } catch (Exception e) {
            throw new CubeApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Your name couldn't be saved.");
        }
        user.setFirstName(name);
    }

    String validName(String raw) {
        String name = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (name.length() < nameMin || name.length() > nameMax) {
            throw CubeApiException.badRequest("Name must be " + nameMin + " to " + nameMax + " characters.");
        }
        if (name.chars().anyMatch(Character::isISOControl) || name.contains("@") || name.contains("://")) {
            throw CubeApiException.badRequest("Name contains characters that aren't allowed.");
        }
        return name;
    }

    /** Alovoa's registration age check (Tools.calcUserAge), enforced at 18+. */
    Date validDob(String raw) {
        if (raw == null || raw.isBlank()) {
            throw CubeApiException.badRequest("Date of birth is required.");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw CubeApiException.badRequest("Date of birth must look like YYYY-MM-DD.");
        }
        if (date.isAfter(LocalDate.now())) {
            throw CubeApiException.badRequest("Date of birth is not valid.");
        }
        Date dob = Tools.localDateToDate(date);
        int age = Tools.calcUserAge(dob);
        if (age < minAge()) {
            throw CubeApiException.badRequest("You must be 18 or older to use Cube Dating.");
        }
        if (age > appMaxAge) {
            throw CubeApiException.badRequest("Date of birth is not valid.");
        }
        return dob;
    }

    private Gender validGender(Long id) {
        if (id == null) {
            throw CubeApiException.badRequest("Please choose your gender.");
        }
        return genderRepo.findById(id).orElseThrow(() -> CubeApiException.badRequest("Please choose a valid gender."));
    }

    private Set<Gender> validPreferredGenders(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw CubeApiException.badRequest("Choose who you'd like to meet.");
        }
        Set<Long> unique = new HashSet<>(ids);
        List<Gender> found = genderRepo.findAllById(unique);
        if (found.size() != unique.size()) {
            throw CubeApiException.badRequest("Please choose valid genders.");
        }
        return new HashSet<>(found);
    }

    private UserIntention validIntention(Long id) {
        if (id == null) {
            throw CubeApiException.badRequest("Choose what you're looking for.");
        }
        return intentionRepo.findById(id).orElseThrow(() -> CubeApiException.badRequest("Choose what you're looking for."));
    }

    /** Alovoa's description rules (UserService.updateDescription): max length, no links. */
    String validDescription(String raw) {
        if (raw == null || raw.strip().isEmpty()) {
            return null;
        }
        String description = raw.strip();
        if (description.length() > descriptionSize) {
            throw CubeApiException.badRequest("Your bio can be at most " + descriptionSize + " characters.");
        }
        if (!new UrlDetector(description, UrlDetectorOptions.Default).detect().isEmpty()) {
            throw CubeApiException.badRequest("Links aren't allowed in your bio.");
        }
        return description;
    }

    /** Alovoa's interest rules (UserService.addInterest): 3-30 chars of [a-zA-Z0-9-], at most 10, lower-cased. */
    List<String> validInterests(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String r : raw) {
            if (r == null) {
                continue;
            }
            String value = r.strip();
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() < interestMinChars || value.length() > interestMaxChars) {
                throw CubeApiException.badRequest("Interests must be " + interestMinChars + " to " + interestMaxChars + " characters.");
            }
            if (!INTEREST.matcher(value).matches()) {
                throw CubeApiException.badRequest("Interests can only contain letters, numbers and dashes.");
            }
            out.add(value.toLowerCase());
        }
        if (out.size() > interestMax) {
            throw CubeApiException.badRequest("You can add up to " + interestMax + " interests.");
        }
        return new ArrayList<>(out);
    }
}
