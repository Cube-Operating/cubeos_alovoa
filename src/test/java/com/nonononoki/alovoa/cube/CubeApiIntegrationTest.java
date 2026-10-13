package com.nonononoki.alovoa.cube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nonononoki.alovoa.cube.auth.CubeIdentityClient;
import com.nonononoki.alovoa.cube.repo.CubeAccountRepository;
import com.nonononoki.alovoa.cube.service.CubeAccountService;
import com.nonononoki.alovoa.entity.User;
import com.nonononoki.alovoa.repo.ConversationRepository;
import com.nonononoki.alovoa.repo.UserRepository;
import com.nonononoki.alovoa.service.MailService;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End to end through the real security chain, Alovoa services and MariaDB, with Cubemail and the
 * Cubeline registrar replaced by a local HTTP fake.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.MethodName.class)
class CubeApiIntegrationTest {

    private static final String API = "/api/v1/cube";
    private static final long MALE = 1, FEMALE = 2;
    // Lagos, Ikeja
    private static final double LAT = 6.60178, LON = 3.35149;

    private static FakeCubeServices fake;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private CubeAccountRepository cubeAccountRepo;
    @Autowired
    private CubeAccountService accountService;
    @Autowired
    private ConversationRepository conversationRepo;
    @Autowired
    private CubeIdentityClient identityClient;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private MailService mailService;

    @BeforeAll
    static void startFake() throws Exception {
        fake = FakeCubeServices.start();
    }

    @AfterAll
    static void stopFake() {
        fake.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("cube.cubemail.url", () -> fake.cubemailUrl());
        r.add("cube.cubemail.me-path", () -> "/wallet/me");
        r.add("cube.registrar.url", () -> fake.registrarUrl());
    }

    @BeforeEach
    void before() {
        cleanUp();
        fake.reset();
        identityClient.evictAll();
    }

    @AfterEach
    void cleanUp() {
        for (User u : userRepo.findAll()) {
            if (!u.isAdmin() && u.getEmail() != null && u.getEmail().endsWith("@cubemail.io")) {
                accountService.deleteUser(u.getId());
            }
        }
        assertEquals(0, cubeAccountRepo.count());
        assertTrue(userRepo.findAll().stream().noneMatch(u -> u.getEmail() != null && u.getEmail().endsWith("@cubemail.io")));
    }

    // ------------------------------------------------------------------------------------ helpers

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode json(ResultActions r) throws Exception {
        MvcResult res = r.andReturn();
        return om.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode getJson(String token, String path) throws Exception {
        return json(mvc.perform(get(API + path).header("Authorization", bearer(token))).andExpect(status().isOk()));
    }

    private static byte[] photo() throws Exception {
        return CubeImageNormalizerTest.png(800, 600, BufferedImage.TYPE_INT_RGB);
    }

    private String data(String name, LocalDate dob, long gender, List<Long> preferred) throws Exception {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("dob", dob.toString());
        d.put("gender", gender);
        d.put("preferredGenders", preferred);
        d.put("intention", 2);
        d.put("description", "Hi, I'm " + name);
        d.put("interests", List.of("Music", "travel"));
        return om.writeValueAsString(d);
    }

    private ResultActions onboard(String token, String data) throws Exception {
        return mvc.perform(multipart(API + "/onboarding")
                .file(new MockMultipartFile("photo", "p.png", "image/png", photo()))
                .file(new MockMultipartFile("data", "", "application/json", data.getBytes()))
                .header("Authorization", bearer(token)));
    }

    /** Registers + onboards a user aged {@code age}, optionally sets a location; returns the token. */
    private String person(String handle, String cubeline, int age, long gender, List<Long> preferred, Double lat, Double lon) throws Exception {
        String token = fake.user(handle, cubeline);
        onboard(token, data(capitalize(handle), LocalDate.now().minusYears(age).minusDays(10), gender, preferred))
                .andExpect(status().isOk()).andExpect(jsonPath("$.onboarded").value(true))
                .andExpect(jsonPath("$.visible").value(false));
        setVisible(token, true).andExpect(status().isOk()).andExpect(jsonPath("$.visible").value(true));
        if (lat != null) {
            location(token, lat, lon);
        }
        return token;
    }

    private ResultActions setVisible(String token, boolean visible) throws Exception {
        return mvc.perform(put(API + "/visibility").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"visible\":" + visible + "}"));
    }

    private ResultActions setVisibleViaProfile(String token, boolean visible) throws Exception {
        return mvc.perform(put(API + "/profile").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"visible\":" + visible + "}"));
    }

    private void location(String token, double lat, double lon) throws Exception {
        mvc.perform(post(API + "/location").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":" + lat + ",\"lon\":" + lon + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String uuidOf(String token) throws Exception {
        String handle = token.substring(4);
        User u = userRepo.findByEmail(handle + "@cubemail.io");
        return u.getUuid().toString();
    }

    private JsonNode discover(String token, int km) throws Exception {
        return getJson(token, "/discover?distanceKm=" + km + "&limit=30");
    }

    private static Set<String> names(JsonNode discover) {
        Set<String> s = new TreeSet<>();
        discover.get("users").forEach(u -> s.add(u.get("name").asText()));
        return s;
    }

    private ResultActions post0(String token, String path) throws Exception {
        return mvc.perform(post(API + path).header("Authorization", bearer(token)));
    }

    // ------------------------------------------------------------------------------- bearer filter

    @Test
    void a_bearerFilter() throws Exception {
        mvc.perform(get(API + "/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").isString());
        mvc.perform(get(API + "/me").header("Authorization", "Basic abc")).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/me").header("Authorization", bearer("forged"))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Your Cube App session has expired. Please sign in again."));

        String token = fake.user("alice", "09000000001");
        fake.reset();
        token = fake.user("alice", "09000000001");
        long usersBefore = userRepo.count();
        MvcResult res = mvc.perform(get(API + "/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarded").value(false))
                .andExpect(jsonPath("$.cubeline").value("09000000001"))
                .andExpect(jsonPath("$.profile.photos").isArray())
                .andReturn();
        // stateless: no session cookie
        assertNull(res.getResponse().getCookie("JSESSIONID"));
        assertNull(res.getResponse().getHeader("Set-Cookie"));

        // auto-created Alovoa user: <handle>@cubemail.io, confirmed, with a password
        assertEquals(usersBefore + 1, userRepo.count());
        assertEquals(1, fake.hits("/api/cubemail/wallet/me"));
        User alice = userRepo.findByEmail("alice@cubemail.io");
        assertNotNull(alice);
        assertTrue(alice.isConfirmed());
        assertNotNull(alice.getPassword());
        assertEquals("09000000001", cubeAccountRepo.findById(alice.getId()).orElseThrow().getCubeline());

        // second request: token cached, same user, registrar not asked again
        getJson(token, "/me");
        getJson(token, "/options");
        assertEquals(1, fake.hits("/api/cubemail/wallet/me"));
        assertEquals(1, fake.hits("/cubeline/me"));
        assertEquals(usersBefore + 1, userRepo.count());

        // POST without CSRF token works on the cube API
        location(token, LAT, LON);

        // Alovoa's web endpoints are not opened by a Cubemail bearer
        mvc.perform(get("/user/status/new-alert").header("Authorization", bearer(token)))
                .andExpect(result -> assertNotEquals(200, result.getResponse().getStatus()));
    }

    @Test
    void b_options() throws Exception {
        String token = fake.user("opt", "09000000002");
        JsonNode o = getJson(token, "/options");
        assertEquals(3, o.get("genders").size());
        assertEquals(1, o.get("genders").get(0).get("id").asLong());
        assertTrue(o.get("genders").get(0).get("name").isTextual());
        assertEquals(3, o.get("intentions").size());
        assertTrue(o.get("interestsSuggestions").size() > 5);
    }

    // ------------------------------------------------------------------------------ onboarding 18+

    @Test
    void c_onboardingEnforces18Plus() throws Exception {
        String token = fake.user("young", "09000000003");
        LocalDate seventeen = LocalDate.now().minusYears(18).plusDays(1);
        onboard(token, data("Young", seventeen, FEMALE, List.of(MALE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("You must be 18 or older to use Cube Dating."));
        assertFalse(getJson(token, "/me").get("onboarded").asBoolean());

        onboard(token, data("Young", LocalDate.now().plusDays(3), FEMALE, List.of(MALE))).andExpect(status().isBadRequest());
        onboard(token, data("Young", LocalDate.now().minusYears(18), FEMALE, List.of(MALE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarded").value(true))
                .andExpect(jsonPath("$.profile.age").value(18))
                .andExpect(jsonPath("$.profile.name").value("Young"))
                .andExpect(jsonPath("$.profile.gender").value(FEMALE))
                .andExpect(jsonPath("$.profile.preferredGenders[0]").value(MALE))
                .andExpect(jsonPath("$.profile.intention").value(2))
                .andExpect(jsonPath("$.profile.minAge").value(18))
                .andExpect(jsonPath("$.profile.interests[0]").value("music"))
                .andExpect(jsonPath("$.profile.photos.length()").value(1));

        // again -> conflict; dob can be corrected but never under 18; min age can't go below 18
        onboard(token, data("Young", LocalDate.now().minusYears(30), FEMALE, List.of(MALE))).andExpect(status().isConflict());
        mvc.perform(put(API + "/profile").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dob\":\"" + LocalDate.now().minusYears(25) + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(API + "/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profile.age").value(25));
        mvc.perform(put(API + "/profile").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dob\":\"" + LocalDate.now().minusYears(16) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(API + "/profile").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"minAge\":16}")).andExpect(status().isBadRequest());
    }

    @Test
    void d_onboardingNeedsCubelineAndValidInput() throws Exception {
        String token = fake.user("nolan", null);
        onboard(token, data("Nolan", LocalDate.now().minusYears(30), MALE, List.of(FEMALE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Your Cubeline isn't set up yet. Get your Cubeline in Cube App, then try again."));

        fake.cubeline("nolan", "09000000004");
        // bad photo
        mvc.perform(multipart(API + "/onboarding")
                        .file(new MockMultipartFile("photo", "p.gif", "image/gif", "GIF89a".getBytes()))
                        .param("data", data("Nolan", LocalDate.now().minusYears(30), MALE, List.of(FEMALE)))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Use a JPEG, PNG or WebP photo."));
        // bad interests / link in bio
        String bad = data("Nolan", LocalDate.now().minusYears(30), MALE, List.of(FEMALE)).replace("\"travel\"", "\"x\"");
        onboard(token, bad).andExpect(status().isBadRequest());
        String link = data("Nolan", LocalDate.now().minusYears(30), MALE, List.of(FEMALE)).replace("Hi, I'm Nolan", "see www.example.com");
        onboard(token, link).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Links aren't allowed in your bio."));
        // data as a plain form field works too
        mvc.perform(multipart(API + "/onboarding")
                        .file(new MockMultipartFile("photo", "p.png", "image/png", photo()))
                        .param("data", data("Nolan", LocalDate.now().minusYears(30), MALE, List.of(FEMALE)))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cubeline").value("09000000004"));
        // endpoints that need a finished profile refuse before onboarding
        String other = fake.user("pending", "09000000005");
        mvc.perform(get(API + "/discover").header("Authorization", bearer(other))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------- profile and photos

    @Test
    void e_profileAndPhotos() throws Exception {
        String t = person("paula", "09000000006", 28, FEMALE, List.of(MALE), LAT, LON);
        JsonNode me = json(mvc.perform(put(API + "/profile").header("Authorization", bearer(t)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Paula Marie\",\"description\":\"New bio\",\"interests\":[\"chess\",\"Music\"],\"minAge\":25,\"maxAge\":40,\"preferredGenders\":[1,3]}"))
                .andExpect(status().isOk()));
        JsonNode p = me.get("profile");
        assertEquals("Paula Marie", p.get("name").asText());
        assertEquals("New bio", p.get("description").asText());
        assertEquals(List.of("chess", "music"), om.convertValue(p.get("interests"), List.class));
        assertEquals(25, p.get("minAge").asInt());
        assertEquals(40, p.get("maxAge").asInt());
        assertEquals(2, p.get("preferredGenders").size());
        // name survives a reload (written past Alovoa's updatable=false mapping)
        assertEquals("Paula Marie", getJson(t, "/me").get("profile").get("name").asText());

        // photos: max 6, first is the profile picture
        String firstId = p.get("photos").get(0).get("id").asText();
        assertEquals("/dating/api/v1/cube/photo/" + firstId, p.get("photos").get(0).get("url").asText());
        List<String> added = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            JsonNode ph = json(mvc.perform(multipart(API + "/photos")
                            .file(new MockMultipartFile("photo", "x.png", "image/png", photo()))
                            .header("Authorization", bearer(t)))
                    .andExpect(status().isOk()));
            added.add(ph.get("id").asText());
            assertTrue(ph.get("url").asText().endsWith(ph.get("id").asText()));
        }
        mvc.perform(multipart(API + "/photos").file(new MockMultipartFile("photo", "x.png", "image/png", photo()))
                        .header("Authorization", bearer(t)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("You can have up to 6 photos."));
        assertEquals(6, getJson(t, "/me").get("profile").get("photos").size());

        // own photo bytes
        byte[] img = mvc.perform(get(API + "/photo/" + firstId).header("Authorization", bearer(t)))
                .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg")).andReturn().getResponse().getContentAsByteArray();
        assertTrue(img.length > 100);

        // delete an extra photo, then the profile picture -> next photo is promoted
        mvc.perform(delete(API + "/photos/" + added.get(4)).header("Authorization", bearer(t))).andExpect(status().isOk());
        mvc.perform(delete(API + "/photos/" + firstId).header("Authorization", bearer(t))).andExpect(status().isOk());
        JsonNode photos = getJson(t, "/me").get("profile").get("photos");
        assertEquals(4, photos.size());
        assertEquals(added.get(0), photos.get(0).get("id").asText());
        mvc.perform(get(API + "/photo/" + added.get(0)).header("Authorization", bearer(t))).andExpect(status().isOk());
        mvc.perform(get(API + "/photo/" + firstId).header("Authorization", bearer(t))).andExpect(status().isNotFound());
        for (int i = 1; i < 4; i++) {
            mvc.perform(delete(API + "/photos/" + added.get(i)).header("Authorization", bearer(t))).andExpect(status().isOk());
        }
        mvc.perform(delete(API + "/photos/" + added.get(0)).header("Authorization", bearer(t)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("You need at least one photo."));
    }

    // ---------------------------------------------------------------------------------- location

    @Test
    void f_locationIsRoundedServerSide() throws Exception {
        String t = person("lola", "09000000007", 30, FEMALE, List.of(MALE), null, null);
        location(t, 52.516789, 13.404449);
        User u = userRepo.findByEmail("lola@cubemail.io");
        assertEquals(52.52, u.getLocationLatitude());
        assertEquals(13.40, u.getLocationLongitude());
        assertNotNull(cubeAccountRepo.findById(u.getId()).orElseThrow().getLocationUpdatedAt());
        mvc.perform(post(API + "/location").header("Authorization", bearer(t)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lat\":91,\"lon\":0}")).andExpect(status().isBadRequest());
        mvc.perform(post(API + "/location").header("Authorization", bearer(t)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lat\":1}")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------------------- discover

    @Test
    void g_discoverExclusions() throws Exception {
        String alice = person("alice", "09000000011", 25, FEMALE, List.of(MALE), LAT, LON);
        String bob = person("bob", "09000000012", 27, MALE, List.of(FEMALE), LAT + 0.001, LON); // same rounded spot
        String ben = person("ben", "09000000013", 29, MALE, List.of(FEMALE), LAT + 0.1, LON);   // ~11 km
        person("carl", "09000000014", 27, MALE, List.of(FEMALE), null, null);                     // no location
        String dave = fake.user("dave", "09000000015");                                           // not onboarded
        getJson(dave, "/me");
        location(dave, LAT, LON);
        String erin = person("erin", "09000000016", 27, MALE, List.of(FEMALE), LAT, LON);         // blocked by alice
        String frank = person("frank", "09000000017", 27, MALE, List.of(FEMALE), LAT, LON);       // blocks alice
        String gus = person("gus", "09000000018", 27, MALE, List.of(FEMALE), LAT, LON);           // liked by alice
        String hank = person("hank", "09000000019", 27, MALE, List.of(FEMALE), LAT, LON);         // passed by alice
        person("ivan", "09000000020", 27, MALE, List.of(MALE), LAT, LON);                         // wants men only
        person("wendy", "09000000021", 27, FEMALE, List.of(MALE), LAT, LON);                      // not a gender alice wants
        String jack = person("jack", "09000000022", 45, MALE, List.of(FEMALE), LAT, LON);         // wants 40+ (alice is 25)
        mvc.perform(put(API + "/profile").header("Authorization", bearer(jack)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"minAge\":40,\"maxAge\":55}")).andExpect(status().isOk());
        person("kyle", "09000000023", 27, MALE, List.of(FEMALE), LAT, LON);                       // stale location
        jdbc.update("UPDATE cube_account SET location_updated_at = ? WHERE user_id = ?",
                new Date(System.currentTimeMillis() - 31L * 24 * 3600 * 1000), userRepo.findByEmail("kyle@cubemail.io").getId());
        person("mike", "09000000024", 27, MALE, List.of(FEMALE), LAT + 0.4, LON + 0.4);          // in the box, outside 50 km circle
        person("leo", "09000000025", 27, MALE, List.of(FEMALE), LAT + 3, LON);                   // ~330 km away
        person("oldie", "09000000026", 60, MALE, List.of(FEMALE), LAT, LON);                     // outside alice's age range

        assertEquals(Set.of("Ben", "Bob", "Erin", "Frank", "Gus", "Hank"), names(discoverBefore(alice)));

        post0(alice, "/block/" + uuidOf(erin)).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        post0(frank, "/block/" + uuidOf(alice)).andExpect(status().isOk());
        post0(alice, "/like/" + uuidOf(gus)).andExpect(status().isOk()).andExpect(jsonPath("$.match").value(false));
        post0(alice, "/pass/" + uuidOf(hank)).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));

        JsonNode d = discover(alice, 50);
        assertEquals(Set.of("Ben", "Bob"), names(d));
        // nearest first, whole km, 0 = under 1 km
        assertEquals("Bob", d.get("users").get(0).get("name").asText());
        assertEquals(0, d.get("users").get(0).get("distanceKm").asInt());
        int benKm = d.get("users").get(1).get("distanceKm").asInt();
        assertTrue(benKm >= 10 && benKm <= 12, "ben at " + benKm);
        assertEquals(Set.of("Bob"), names(discover(alice, 5)));
        assertEquals(1, getJson(alice, "/discover?distanceKm=50&limit=1").get("users").size());

        // card shape: no cubeline, email, handle or coordinates
        JsonNode card = d.get("users").get(0);
        assertEquals(Set.of("uuid", "name", "age", "distanceKm", "photos", "description", "interests", "verified", "intention"),
                fieldNames(card));
        String raw = d.toString();
        assertFalse(raw.contains("0900000001"), raw);
        assertFalse(raw.contains("cubemail"), raw);
        assertFalse(raw.contains("6.60") || raw.contains("3.35"), raw);
        assertTrue(card.get("photos").get(0).asText().startsWith("/dating/api/v1/cube/photo/"));

        // photo visibility: alice may see bob's photo; blocked erin and not-onboarded dave may not see alice's
        String bobPhoto = card.get("photos").get(0).asText().substring("/dating".length());
        mvc.perform(get(bobPhoto).header("Authorization", bearer(alice))).andExpect(status().isOk());
        String alicePhoto = getJson(alice, "/me").get("profile").get("photos").get(0).get("url").asText().substring("/dating".length());
        mvc.perform(get(alicePhoto).header("Authorization", bearer(bob))).andExpect(status().isOk());
        mvc.perform(get(alicePhoto).header("Authorization", bearer(erin))).andExpect(status().isNotFound());
        mvc.perform(get(alicePhoto).header("Authorization", bearer(frank))).andExpect(status().isNotFound());
        mvc.perform(get(alicePhoto).header("Authorization", bearer(dave))).andExpect(status().isNotFound());

        // can't like blocked people or yourself
        post0(alice, "/like/" + uuidOf(erin)).andExpect(status().isNotFound());
        post0(frank, "/like/" + uuidOf(alice)).andExpect(status().isNotFound());
        post0(alice, "/like/" + uuidOf(alice)).andExpect(status().isNotFound());

        // discovery requires a fresh location of your own
        jdbc.update("UPDATE cube_account SET location_updated_at = ? WHERE user_id = ?",
                new Date(System.currentTimeMillis() - 31L * 24 * 3600 * 1000), userRepo.findByEmail("alice@cubemail.io").getId());
        mvc.perform(get(API + "/discover").header("Authorization", bearer(alice))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Share your location to discover people nearby."));
        assertNotNull(bob);
    }

    /** Discover before alice interacts with anyone (everyone compatible within 50 km). */
    private JsonNode discoverBefore(String token) throws Exception {
        return discover(token, 50);
    }

    private static Set<String> fieldNames(JsonNode n) {
        Set<String> s = new HashSet<>();
        n.fieldNames().forEachRemaining(s::add);
        return s;
    }

    // ------------------------------------------------------------------------- like -> match

    @Test
    void h_likeMatchAndCubelineExposure() throws Exception {
        String alice = person("alice", "09000000031", 25, FEMALE, List.of(MALE), LAT, LON);
        String bob = person("bob", "09000000032", 27, MALE, List.of(FEMALE), LAT, LON);
        String carl = person("carl", "09000000033", 27, MALE, List.of(FEMALE), LAT, LON);
        String aliceId = uuidOf(alice), bobId = uuidOf(bob);

        // one-sided like: no match, no cubeline anywhere
        JsonNode like = json(post0(alice, "/like/" + bobId).andExpect(status().isOk()));
        assertFalse(like.get("match").asBoolean());
        assertFalse(like.has("match_info"));
        assertEquals(0, getJson(alice, "/matches").get("matches").size());
        assertEquals(0, getJson(bob, "/matches").get("matches").size());
        JsonNode bobsView = discover(bob, 50);
        assertTrue(names(bobsView).contains("Alice"));
        assertFalse(bobsView.toString().contains("09000000031"));
        // alice no longer sees bob after liking him
        assertFalse(names(discover(alice, 50)).contains("Bob"));

        // mutual like: match with the other's cubeline
        JsonNode match = json(post0(bob, "/like/" + aliceId).andExpect(status().isOk()));
        assertTrue(match.get("match").asBoolean());
        assertEquals(aliceId, match.get("match_info").get("uuid").asText());
        assertEquals("Alice", match.get("match_info").get("name").asText());
        assertEquals("09000000031", match.get("match_info").get("cubeline").asText());
        assertTrue(match.get("match_info").get("photo").asText().startsWith("/dating/api/v1/cube/photo/"));

        JsonNode am = getJson(alice, "/matches").get("matches");
        assertEquals(1, am.size());
        assertEquals(bobId, am.get(0).get("uuid").asText());
        assertEquals("09000000032", am.get(0).get("cubeline").asText());
        assertEquals(27, am.get(0).get("age").asInt());
        assertNotNull(Date.from(java.time.Instant.parse(am.get(0).get("matchedAt").asText())));
        assertEquals("09000000031", getJson(bob, "/matches").get("matches").get(0).get("cubeline").asText());

        // a third user never sees either cubeline
        assertEquals(0, getJson(carl, "/matches").get("matches").size());
        String carlsView = discover(carl, 50).toString();
        assertFalse(carlsView.contains("09000000031") || carlsView.contains("09000000032"), carlsView);

        // no Alovoa conversation is created for Cube App matches
        User a = userRepo.findByEmail("alice@cubemail.io");
        assertTrue(conversationRepo.findByUsers_Id(a.getId()).isEmpty());

        // blocking hides the match (and the cubeline) on both sides
        post0(bob, "/block/" + aliceId).andExpect(status().isOk());
        assertEquals(0, getJson(alice, "/matches").get("matches").size());
        assertEquals(0, getJson(bob, "/matches").get("matches").size());
    }

    @Test
    void i_unmatchReportAndDelete() throws Exception {
        String alice = person("alice", "09000000041", 25, FEMALE, List.of(MALE), LAT, LON);
        String bob = person("bob", "09000000042", 27, MALE, List.of(FEMALE), LAT, LON);
        String carl = person("carl", "09000000043", 27, MALE, List.of(FEMALE), LAT, LON);
        post0(alice, "/like/" + uuidOf(bob));
        post0(bob, "/like/" + uuidOf(alice)).andExpect(jsonPath("$.match").value(true));

        mvc.perform(delete(API + "/matches/" + uuidOf(bob)).header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertEquals(0, getJson(alice, "/matches").get("matches").size());
        assertEquals(0, getJson(bob, "/matches").get("matches").size());
        // hidden both ways
        assertFalse(names(discover(alice, 50)).contains("Bob"));
        assertFalse(names(discover(bob, 50)).contains("Alice"));
        mvc.perform(delete(API + "/matches/" + uuidOf(bob)).header("Authorization", bearer(alice)))
                .andExpect(status().isNotFound());

        // report needs a reason, then hides the person
        mvc.perform(post(API + "/report/" + uuidOf(carl)).header("Authorization", bearer(alice))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(API + "/report/" + uuidOf(carl)).header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Spam profile\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertFalse(names(discover(alice, 50)).contains("Carl"));

        // unknown uuid
        post0(alice, "/like/" + UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("This profile is no longer available."));
        post0(alice, "/like/not-a-uuid").andExpect(status().isNotFound());

        // delete the dating profile
        String carlId = uuidOf(carl);
        mvc.perform(delete(API + "/me").header("Authorization", bearer(carl))).andExpect(status().isOk());
        assertNull(userRepo.findByEmail("carl@cubemail.io"));
        post0(alice, "/like/" + carlId).andExpect(status().isNotFound());
        // the same Cube App account can start over
        assertFalse(getJson(carl, "/me").get("onboarded").asBoolean());
        Mockito.verifyNoInteractions(mailService);
    }

    // -------------------------------------------------------------------------- opt-in visibility

    @Test
    void j_visibilityIsOptIn() throws Exception {
        // default hidden, and can't be turned on before onboarding
        String newbie = fake.user("newbie", "09000000051");
        JsonNode me = getJson(newbie, "/me");
        assertFalse(me.get("visible").asBoolean());
        setVisible(newbie, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Set up your dating profile first"))
                .andExpect(jsonPath("$.code").value("not_onboarded"));
        setVisibleViaProfile(newbie, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_onboarded"));
        setVisible(newbie, false).andExpect(status().isOk()).andExpect(jsonPath("$.visible").value(false));
        onboard(newbie, data("Newbie", LocalDate.now().minusYears(24), FEMALE, List.of(MALE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visible").value(false));
        location(newbie, LAT, LON);
        assertFalse(getJson(newbie, "/me").get("visible").asBoolean());

        String alice = person("alice", "09000000052", 25, FEMALE, List.of(MALE), LAT, LON);
        String bob = person("bob", "09000000053", 27, MALE, List.of(FEMALE), LAT, LON);

        // a hidden user is excluded from others' discover
        assertEquals(Set.of("Alice"), names(discover(bob, 50)));
        assertFalse(names(discover(bob, 50)).contains("Newbie"));

        // browse privately: a hidden user can discover, but can't like; pass only affects their own deck
        assertEquals(Set.of("Bob"), names(discover(newbie, 50)));
        post0(newbie, "/like/" + uuidOf(bob)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Turn on \"See and be seen\" in Settings to like people"))
                .andExpect(jsonPath("$.code").value("not_visible"));
        assertEquals(Set.of("Bob"), names(discover(newbie, 50)));
        post0(newbie, "/pass/" + uuidOf(bob)).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertEquals(Set.of(), names(discover(newbie, 50)));
        // ...and browsing/passing left no trace for bob: no like, no block, still unaware of newbie
        assertEquals(0, getJson(bob, "/matches").get("matches").size());
        assertFalse(names(discover(bob, 50)).contains("Newbie"));
        String bobPhoto = getJson(bob, "/me").get("profile").get("photos").get(0).get("url").asText().substring("/dating".length());
        mvc.perform(get(bobPhoto).header("Authorization", bearer(newbie))).andExpect(status().isOk());
        // hidden profiles can't be liked by others
        post0(bob, "/like/" + uuidOf(newbie)).andExpect(status().isNotFound());

        // toggle on via PUT /profile: appears in discover
        setVisibleViaProfile(newbie, true).andExpect(status().isOk()).andExpect(jsonPath("$.visible").value(true));
        assertTrue(getJson(newbie, "/me").get("visible").asBoolean());
        assertEquals(Set.of("Alice", "Newbie"), names(discover(bob, 50)));
        assertEquals(Set.of(), names(discover(newbie, 50))); // newbie passed bob earlier

        // match while visible, then hide: matches, block/report and profile editing keep working
        post0(alice, "/like/" + uuidOf(bob)).andExpect(jsonPath("$.match").value(false));
        post0(bob, "/like/" + uuidOf(alice)).andExpect(jsonPath("$.match").value(true));
        setVisible(alice, false).andExpect(status().isOk()).andExpect(jsonPath("$.visible").value(false));
        assertEquals(Set.of("Newbie"), names(discover(bob, 50)));
        assertEquals(Set.of(), names(discover(alice, 50))); // 200, not 403: alice browses privately (bob liked, newbie wants men)
        post0(alice, "/like/" + uuidOf(newbie)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("not_visible"));
        JsonNode am = getJson(alice, "/matches").get("matches");
        assertEquals(1, am.size());
        assertEquals("09000000053", am.get(0).get("cubeline").asText());
        assertEquals("09000000052", getJson(bob, "/matches").get("matches").get(0).get("cubeline").asText());
        mvc.perform(put(API + "/profile").header("Authorization", bearer(alice)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"still here\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.visible").value(false));
        mvc.perform(post(API + "/report/" + uuidOf(newbie)).header("Authorization", bearer(alice))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}")).andExpect(status().isOk());
        post0(alice, "/block/" + uuidOf(newbie)).andExpect(status().isOk());

        // toggle off and on again via the dedicated endpoint
        setVisible(newbie, false).andExpect(jsonPath("$.visible").value(false));
        assertEquals(Set.of(), names(discover(bob, 50)));
        setVisible(newbie, true).andExpect(jsonPath("$.visible").value(true));
        assertEquals(Set.of("Newbie"), names(discover(bob, 50)));
        mvc.perform(put(API + "/visibility").header("Authorization", bearer(bob)).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
    }
}
