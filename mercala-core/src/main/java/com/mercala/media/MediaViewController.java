package com.mercala.media;

import java.net.URI;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mercala.platform.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;

/**
 * Lets a merchant look at their own uploaded photograph.
 *
 * <p>Those live in the private bucket — see {@link MediaObjectStorage} for why they stay
 * there — so the dashboard cannot put the storage URL in an {@code <img>} tag and get
 * anything but a 403. This endpoint authenticates the caller, checks that the object is
 * theirs, and redirects to a short-lived presigned URL. The bytes never pass through the
 * API; the browser fetches them from storage directly and can cache them for the life of
 * the signature.
 *
 * <p>Finished product imagery does not come through here. It is public, and a redirect per
 * image would be a pointless round trip on a page showing a dozen of them.
 */
@RestController
@RequestMapping("/api/media")
public class MediaViewController {

    private static final Logger log = LoggerFactory.getLogger(MediaViewController.class);

    /**
     * Long enough for a page to load its images and for a merchant to sit looking at one;
     * short enough that a URL copied out of devtools is not a lasting grant.
     */
    private static final Duration TTL = Duration.ofMinutes(15);

    private final MediaObjectStorage storage;

    public MediaViewController(MediaObjectStorage storage) {
        this.storage = storage;
    }

    @Operation(
            summary = "Open one of your own uploaded images",
            description = """
                    Redirects to a presigned URL valid for fifteen minutes. The image must
                    belong to the caller's store; another tenant's object is a 403 whether
                    or not it exists.
                    """)
    @GetMapping("/view")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> view(
            @RequestParam("url") String url,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        if (principal == null || principal.tenantId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No store to read images for");
        }

        String key;
        try {
            key = storage.objectKeyOf(url);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        // The tenant prefix on the key is the ownership record. It is why uploads are
        // named `<tenant>/uploads/<uuid>.<ext>` rather than `<uuid>.<ext>` — without it
        // this check would need a database lookup, and a missing row would be
        // indistinguishable from someone else's file.
        if (!key.startsWith(principal.tenantId() + "/")) {
            log.warn("Tenant {} tried to view an object outside its prefix", principal.tenantId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That image belongs to another store");
        }

        String presigned;
        try {
            presigned = storage.presignedView(key, TTL);
        } catch (MediaObjectStorage.MediaStorageException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }

        // 302, not 301: the signature expires, and a permanently-cached redirect to a URL
        // that stops working in fifteen minutes is a bug with a long tail.
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(presigned)).build();
    }
}
