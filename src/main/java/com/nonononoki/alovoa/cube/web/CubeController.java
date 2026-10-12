package com.nonononoki.alovoa.cube.web;

import com.nonononoki.alovoa.cube.auth.CubeSecurityConfig;
import com.nonononoki.alovoa.cube.auth.CubemailBearerFilter;
import com.nonononoki.alovoa.cube.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * CubeOS: the Cube App dating API. Every endpoint requires {@code Authorization: Bearer <cubemail token>}
 * (see {@link CubemailBearerFilter}); errors are {@code {"error": "user-facing message"}}.
 */
@RestController
@RequestMapping(value = CubeSecurityConfig.API_PREFIX, produces = MediaType.APPLICATION_JSON_VALUE)
public class CubeController {

    private static final Map<String, Object> OK = Map.of("ok", true);

    @Autowired
    private CubeProfileService profileService;
    @Autowired
    private CubeDiscoverService discoverService;
    @Autowired
    private CubeMatchService matchService;
    @Autowired
    private CubePhotoService photoService;
    @Autowired
    private CubeAccountService accountService;
    @Autowired
    private CubeAccess access;

    public record LocationInput(Double lat, Double lon) {
    }

    public record ReportInput(String reason) {
    }

    public record VisibilityInput(Boolean visible) {
    }

    // ---- profile

    @GetMapping("/me")
    public Map<String, Object> me() {
        return profileService.me();
    }

    @GetMapping("/options")
    public Map<String, Object> options() {
        return profileService.options();
    }

    @PostMapping("/onboarding")
    public Map<String, Object> onboarding(HttpServletRequest request) throws Exception {
        MultipartHttpServletRequest multipart = multipart(request);
        String data = multipart.getParameter("data");
        if (data == null) {
            MultipartFile dataPart = multipart.getFile("data");
            data = dataPart == null ? null : new String(dataPart.getBytes(), StandardCharsets.UTF_8);
        }
        CubeProfileService.ProfileInput input = profileService.parseInput(data);
        String bearer = (String) request.getAttribute(CubemailBearerFilter.ATTR_TOKEN);
        return profileService.onboard(multipart.getFile("photo"), input, bearer);
    }

    @PutMapping(value = "/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> updateProfile(@RequestBody CubeProfileService.ProfileInput input) throws Exception {
        return profileService.update(input);
    }

    @PutMapping(value = "/visibility", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> visibility(@RequestBody(required = false) VisibilityInput input) {
        return profileService.setVisibility(input == null ? null : input.visible());
    }

    @PostMapping("/photos")
    public CubeViews.Photo addPhoto(HttpServletRequest request) throws Exception {
        return profileService.addPhoto(multipart(request).getFile("photo"));
    }

    @DeleteMapping("/photos/{id}")
    public Map<String, Object> deletePhoto(@PathVariable("id") String id) throws Exception {
        profileService.deletePhoto(id);
        return OK;
    }

    @PostMapping(value = "/location", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> location(@RequestBody LocationInput input) {
        if (input == null) {
            throw CubeApiException.badRequest("Location is not valid.");
        }
        profileService.updateLocation(input.lat(), input.lon());
        return OK;
    }

    // ---- discover and swipe

    @GetMapping("/discover")
    public Map<String, Object> discover(@RequestParam(value = "distanceKm", required = false) Integer distanceKm,
                                        @RequestParam(value = "limit", required = false) Integer limit) throws Exception {
        return discoverService.discover(distanceKm, limit);
    }

    @PostMapping("/like/{uuid}")
    public Map<String, Object> like(@PathVariable("uuid") String uuid) {
        return matchService.like(uuid);
    }

    @PostMapping("/pass/{uuid}")
    public Map<String, Object> pass(@PathVariable("uuid") String uuid) throws Exception {
        matchService.pass(uuid);
        return OK;
    }

    @GetMapping("/matches")
    public Map<String, Object> matches() {
        return matchService.matches();
    }

    @DeleteMapping("/matches/{uuid}")
    public Map<String, Object> unmatch(@PathVariable("uuid") String uuid) {
        matchService.unmatch(uuid);
        return OK;
    }

    @PostMapping("/block/{uuid}")
    public Map<String, Object> block(@PathVariable("uuid") String uuid) throws Exception {
        matchService.block(uuid);
        return OK;
    }

    @PostMapping("/report/{uuid}")
    public Map<String, Object> report(@PathVariable("uuid") String uuid,
                                      @RequestBody(required = false) ReportInput input) throws Exception {
        matchService.report(uuid, input == null ? null : input.reason());
        return OK;
    }

    // ---- media and account

    @GetMapping(value = "/photo/{id}", produces = MediaType.ALL_VALUE)
    public ResponseEntity<byte[]> photo(@PathVariable("id") String id) {
        CubePhotoService.PhotoBytes p = photoService.photo(id);
        MediaType type;
        try {
            type = MediaType.parseMediaType(p.mime());
        } catch (Exception e) {
            type = MediaType.IMAGE_JPEG;
        }
        return ResponseEntity.ok().contentType(type)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(p.bytes());
    }

    @DeleteMapping("/me")
    public Map<String, Object> deleteMe() {
        accountService.deleteUser(access.currentUser().getId());
        return OK;
    }

    private static MultipartHttpServletRequest multipart(HttpServletRequest request) {
        MultipartHttpServletRequest m = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        if (m != null) {
            return m;
        }
        throw CubeApiException.badRequest("Please send the photo as multipart/form-data.");
    }
}
