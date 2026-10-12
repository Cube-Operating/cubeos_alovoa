package com.nonononoki.alovoa.cube.service;

import com.nonononoki.alovoa.cube.repo.CubeAccountRepository;
import com.nonononoki.alovoa.cube.web.CubeApiException;
import com.nonononoki.alovoa.entity.User;
import com.nonononoki.alovoa.entity.user.UserImage;
import com.nonononoki.alovoa.entity.user.UserProfilePicture;
import com.nonononoki.alovoa.repo.UserImageRepository;
import com.nonononoki.alovoa.repo.UserProfilePictureRepository;
import com.nonononoki.alovoa.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.UUID;

/**
 * CubeOS: serves photo bytes to the Cube App. A user always sees their own photos; other people's only
 * when the viewer is onboarded and the owner is an active Cube profile with no block either way.
 */
@Service
public class CubePhotoService {

    @Autowired
    private CubeAccess access;
    @Autowired
    private CubeAccountRepository cubeAccountRepo;
    @Autowired
    private UserProfilePictureRepository profilePictureRepo;
    @Autowired
    private UserImageRepository imageRepo;

    public record PhotoBytes(byte[] bytes, String mime) {
    }

    public PhotoBytes photo(String photoId) {
        User viewer = access.currentUser();
        UUID id;
        try {
            id = UUID.fromString(photoId);
        } catch (IllegalArgumentException e) {
            throw CubeApiException.notFound("Photo not found.");
        }
        byte[] bin;
        String mime;
        String legacyData;
        User owner;
        UserProfilePicture pic = profilePictureRepo.findByUuid(id);
        if (pic != null) {
            owner = cubeAccountRepo.findUserByProfilePictureUuid(id);
            bin = pic.getBin();
            mime = pic.getBinMime();
            legacyData = pic.getData();
        } else {
            UserImage img = imageRepo.findByUuid(id);
            if (img == null) {
                throw CubeApiException.notFound("Photo not found.");
            }
            owner = img.getUser();
            bin = img.getBin();
            mime = img.getBinMime();
            legacyData = img.getContent();
        }
        if (owner == null) {
            throw CubeApiException.notFound("Photo not found.");
        }
        if (!owner.getId().equals(viewer.getId())) {
            if (!CubeAccess.isOnboarded(viewer) || !access.isActiveCubeProfile(owner) || access.blockedEitherWay(viewer, owner)) {
                throw CubeApiException.notFound("Photo not found.");
            }
        }
        if (bin == null && legacyData != null) {
            // pre-binary Alovoa rows: data:image/<mime>;base64,<...>
            String b64 = UserService.stripB64Type(legacyData);
            bin = Base64.getDecoder().decode(b64);
            if (legacyData.startsWith("data:") && legacyData.contains(";")) {
                mime = legacyData.substring(5, legacyData.indexOf(';'));
            }
        }
        if (bin == null) {
            throw CubeApiException.notFound("Photo not found.");
        }
        return new PhotoBytes(bin, mime == null ? "image/jpeg" : mime);
    }
}
