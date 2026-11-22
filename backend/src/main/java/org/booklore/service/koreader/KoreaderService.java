package org.booklore.service.koreader;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.userdetails.KoreaderUserDetails;
import org.booklore.config.BookmarkProperties;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.koreader.KoreaderAnnotationRejection;
import org.booklore.model.dto.koreader.KoreaderAnnotationSyncItem;
import org.booklore.model.dto.koreader.KoreaderAnnotationSyncRequest;
import org.booklore.model.dto.koreader.KoreaderAnnotationSyncResponse;
import org.booklore.model.dto.progress.KoreaderProgress;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.*;
import org.booklore.service.hardcover.HardcoverSyncService;
import org.booklore.util.koreader.EpubCfiService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@AllArgsConstructor
@Service
public class KoreaderService {
    private static final String DEFAULT_DEVICE_NAME = "Grimmory";
    private static final String DEFAULT_DEVICE_ID = "Grimmory";

    private final UserBookProgressRepository progressRepository;
    private final UserBookFileProgressRepository fileProgressRepository;
    private final AnnotationRepository annotationRepository;
    private final BookNoteV2Repository bookNoteV2Repository;
    private final BookMarkRepository bookMarkRepository;
    private final BookRepository bookRepository;
    private final UserRepository userRepository;
    private final KoreaderUserRepository koreaderUserRepository;
    private final HardcoverSyncService hardcoverSyncService;
    private final EpubCfiService epubCfiService;
    private final BookmarkProperties bookmarkProperties;

    public ResponseEntity<Map<String, String>> authorizeUser() {
        KoreaderUserDetails authDetails = getAuthDetails();
        KoreaderUserEntity koreaderUser = findKoreaderUser(authDetails.getUsername());
        validatePassword(koreaderUser, authDetails);

        log.info("User '{}' authorized", authDetails.getUsername());
        return ResponseEntity.ok(Map.of("username", authDetails.getUsername()));
    }

    public KoreaderProgress getProgress(String bookHash) {
        KoreaderUserDetails authDetails = getAuthDetailsWithSyncCheck();
        BookEntity book = findBookByHash(bookHash);
        UserBookProgressEntity progress = findUserProgress(authDetails.getBookLoreUserId(), book.getId());

        log.info("getProgress: fetched progress='{}' percentage={} for userId={} bookHash={}",
                progress.getKoreaderProgress(), progress.getKoreaderProgressPercent(),
                authDetails.getBookLoreUserId(), bookHash);

        Long timestamp = progress.getKoreaderLastSyncTime() != null
                ? progress.getKoreaderLastSyncTime().getEpochSecond()
                : null;

        String device = progress.getKoreaderDevice() != null ?
                progress.getKoreaderDevice() :
                DEFAULT_DEVICE_NAME;

        String deviceId = progress.getKoreaderDeviceId() != null ?
                progress.getKoreaderDeviceId() :
                DEFAULT_DEVICE_ID;

        return KoreaderProgress.builder()
                .timestamp(timestamp)
                .document(bookHash)
                .progress(progress.getKoreaderProgress())
                .percentage(progress.getKoreaderProgressPercent())
                .device(device)
                .device_id(deviceId)
                .build();
    }

    @Transactional
    public void saveProgress(String bookHash, KoreaderProgress koProgress) {
        KoreaderUserDetails authDetails = getAuthDetailsWithSyncCheck();
        BookEntity book = findBookByHash(bookHash);
        BookLoreUserEntity user = findBookLoreUser(authDetails.getBookLoreUserId());

        UserBookProgressEntity userProgress = getOrCreateUserProgress(user, book);
        Float previousProgressPercent = userProgress.getKoreaderProgressPercent();
        ReadStatus previousReadStatus = userProgress.getReadStatus();
        updateProgressData(userProgress, koProgress, book);

        progressRepository.save(userProgress);

        // Also save to file-level progress table (dual-write)
        saveToFileProgress(user, book, userProgress);

        log.info("saveProgress: saved progress='{}' percentage={} for userId={} bookHash={}", koProgress.getProgress(),
                koProgress.getPercentage(), authDetails.getBookLoreUserId(), bookHash);

        // Sync progress to Hardcover asynchronously (if enabled for this user)
        // But only if the progress percentage has changed from last time, or the read
        // status has changed
        if (koProgress.getPercentage() != null && (!koProgress.getPercentage().equals(previousProgressPercent)
                || userProgress.getReadStatus() != previousReadStatus)) {
            Float progressPercent = normalizeProgressPercent(koProgress.getPercentage());
            hardcoverSyncService.syncProgressToHardcover(book.getId(), progressPercent,
                    authDetails.getBookLoreUserId());
        }
    }

    @Transactional
    public KoreaderAnnotationSyncResponse syncAnnotations(KoreaderAnnotationSyncRequest request) {
        KoreaderUserDetails authDetails = getAuthDetailsWithSyncCheck();
        String bookHash = request.getDocument();
        Long userId = authDetails.getBookLoreUserId();

        var maybeBook = bookRepository.findByCurrentHash(bookHash);
        if (maybeBook.isEmpty()) {
            List<KoreaderAnnotationRejection> rejections = new ArrayList<>();
            for (int i = 0; i < request.getAnnotations().size(); i++) {
                KoreaderAnnotationSyncItem item = request.getAnnotations().get(i);
                rejections.add(KoreaderAnnotationRejection.builder()
                        .index(i)
                        .location(item.getLocation())
                        .reason("book_not_found_for_hash")
                        .build());
            }

            return KoreaderAnnotationSyncResponse.builder()
                    .bookId(null)
                    .matchedBook(false)
                    .matchedBy("hash")
                    .created(0)
                    .updated(0)
                    .skippedDuplicates(0)
                    .rejected(rejections.size())
                    .rejectionReasons(rejections)
                    .build();
        }

        BookEntity book = maybeBook.get();
        BookLoreUserEntity user = findBookLoreUser(userId);

        int created = 0;
        int updated = 0;
        int skippedDuplicates = 0;
        List<KoreaderAnnotationRejection> rejections = new ArrayList<>();

        for (int i = 0; i < request.getAnnotations().size(); i++) {
            KoreaderAnnotationSyncItem item = request.getAnnotations().get(i);
            try {
                SyncResult result = syncAnnotationItem(book, user, userId, item, request.getDevice());
                if (result == SyncResult.CREATED) {
                    created++;
                } else if (result == SyncResult.UPDATED) {
                    updated++;
                } else {
                    skippedDuplicates++;
                }
            } catch (DataIntegrityViolationException ex) {
                // Unique (user_id, book_id, cfi) ensures idempotency under concurrent sync
                // calls.
                skippedDuplicates++;
            } catch (Exception ex) {
                log.warn("Failed to sync KOReader annotation at index {} for userId={} bookId={}: {}",
                        i, userId, book.getId(), ex.getMessage());
                rejections.add(KoreaderAnnotationRejection.builder()
                        .index(i)
                        .location(item.getLocation())
                        .reason("invalid_annotation_payload")
                        .build());
            }
        }

        return KoreaderAnnotationSyncResponse.builder()
                .bookId(book.getId())
                .matchedBook(true)
                .matchedBy("hash")
                .created(created)
                .updated(updated)
                .skippedDuplicates(skippedDuplicates)
                .rejected(rejections.size())
                .rejectionReasons(rejections)
                .build();
    }

    @Transactional
    public void syncProgressToKoreader(long bookId, float percentage, long userId) {
        try {
            koreaderUserRepository.findByBookLoreUserId(userId)
                    .filter(KoreaderUserEntity::isSyncEnabled)
                    .ifPresent(koreaderUser -> {
                        BookEntity book = bookRepository.findById(bookId).orElse(null);
                        BookLoreUserEntity user = userRepository.findById(userId).orElse(null);
                        if (book == null || user == null) {
                            log.warn("KOReader sync skipped: bookId={} or userId={} was not found", bookId, userId);
                            return;
                        }

                        UserBookProgressEntity progress = getOrCreateUserProgress(user, book);

                        float koreaderPercent = percentage / 100.0f;
                        progress.setKoreaderProgressPercent(koreaderPercent);
                        progress.setKoreaderLastSyncTime(Instant.now());
                        progress.setKoreaderProgress(null);

                        progress.setKoreaderDevice(DEFAULT_DEVICE_NAME);
                        progress.setKoreaderDeviceId(DEFAULT_DEVICE_ID);

                        BookFileEntity primaryFile = book.getPrimaryBookFile();
                        if (primaryFile != null && primaryFile.getBookType() == BookFileType.EPUB
                                && progress.getEpubProgress() != null) {
                            try {
                                String xpointer = epubCfiService.convertCfiToProgressXPointer(
                                        primaryFile.getFullFilePath(), progress.getEpubProgress());
                                progress.setKoreaderProgress(xpointer);
                            } catch (Exception e) {
                                progress.setKoreaderProgress(null);
                                log.warn("Failed to convert CFI to XPointer for KOReader sync", e);
                            }
                        }

                        progressRepository.save(progress);
                    });
        } catch (Exception e) {
            log.warn("Failed to sync progress to KOReader for userId={} bookId={}", userId, bookId, e);
        }
    }
    private void saveToFileProgress(BookLoreUserEntity user, BookEntity book, UserBookProgressEntity progress) {
        try {
            BookFileEntity primaryFile = book.getPrimaryBookFile();
            UserBookFileProgressEntity fileProgress = fileProgressRepository
                    .findByUserIdAndBookFileId(user.getId(), primaryFile.getId())
                    .orElseGet(UserBookFileProgressEntity::new);

            fileProgress.setUser(user);
            fileProgress.setBookFile(primaryFile);
            fileProgress.setLastReadTime(progress.getLastReadTime());

            // Map progress based on book type
            switch (primaryFile.getBookType()) {
                case EPUB, FB2, MOBI, AZW3 -> {
                    fileProgress.setPositionData(progress.getEpubProgress());
                    fileProgress.setPositionHref(progress.getEpubProgressHref());
                    fileProgress.setProgressPercent(progress.getEpubProgressPercent());
                }
                case PDF -> {
                    fileProgress.setPositionData(
                            progress.getPdfProgress() != null ? String.valueOf(progress.getPdfProgress()) : null);
                    fileProgress.setProgressPercent(progress.getPdfProgressPercent());
                }
                case CBX -> {
                    fileProgress.setPositionData(
                            progress.getCbxProgress() != null ? String.valueOf(progress.getCbxProgress()) : null);
                    fileProgress.setProgressPercent(progress.getCbxProgressPercent());
                }
                case AUDIOBOOK -> {
                    // KOReader does not sync audiobook positions.
                }
            }

            fileProgressRepository.save(fileProgress);
        } catch (Exception e) {
            log.warn("Failed to save file-level progress for book {}: {}", book.getId(), e.getMessage());
        }
    }

    private void updateProgressData(UserBookProgressEntity userProgress, KoreaderProgress koProgress, BookEntity book) {
        userProgress.setKoreaderProgress(koProgress.getProgress());
        userProgress.setKoreaderProgressPercent(koProgress.getPercentage());
        userProgress.setKoreaderDevice(koProgress.getDevice());
        userProgress.setKoreaderDeviceId(koProgress.getDevice_id());
        userProgress.setKoreaderLastSyncTime(Instant.now());
        userProgress.setLastReadTime(Instant.now());
        if (koProgress.getProgress() != null) {
            try {
                String cfi = epubCfiService.convertXPointerToCfi(book.getFullFilePath(), koProgress.getProgress());

                float percent = koProgress.getPercentage() * 100f;
                float rounded = BigDecimal
                        .valueOf(percent)
                        .setScale(1, RoundingMode.HALF_UP)
                        .floatValue();

                userProgress.setEpubProgress(cfi);
                userProgress.setEpubProgressPercent(rounded);

                log.info("Converted xpointer to CFI for BookLore reader sync: {}", cfi);
            } catch (Exception e) {
                log.warn("Failed to convert xpointer to CFI: {}", e.getMessage());
            }
        }

        updateReadStatus(userProgress, koProgress.getPercentage());
    }

    private void updateReadStatus(UserBookProgressEntity userProgress, Float progressFraction) {
        if (progressFraction == null) {
            return;
        }
        double progressPercent = progressFraction * 100.0;
        if (progressPercent >= 99.5) {
            userProgress.setReadStatus(ReadStatus.READ);
            userProgress.setDateFinished(Instant.now());
        } else if (progressPercent >= 0.25) {
            userProgress.setReadStatus(ReadStatus.READING);
        } else {
            userProgress.setReadStatus(ReadStatus.UNREAD);
        }
    }

    private Float normalizeProgressPercent(Float progress) {
        if (progress == null) {
            return null;
        }
        if (progress <= 1.0f) {
            return progress * 100.0f;
        }
        return progress;
    }

    private AnnotationEntity buildAnnotation(BookEntity book, BookLoreUserEntity user, KoreaderAnnotationSyncItem item,
            String device) {
        LocalDateTime annotatedAt = toLocalDateTime(item.getAnnotatedAt());

        return AnnotationEntity.builder()
                .book(book)
                .user(user)
                .cfi(item.getLocation())
                .text(item.getSelectedText())
                .note(item.getNoteContent())
                .chapterTitle(item.getChapterTitle())
                .color(item.getColor() == null ? "#FFFF00" : item.getColor())
                .style("highlight")
                .createdAt(annotatedAt)
                .updatedAt(annotatedAt)
                .build();
    }

    private BookNoteV2Entity buildNote(BookEntity book, BookLoreUserEntity user, KoreaderAnnotationSyncItem item) {
        LocalDateTime annotatedAt = toLocalDateTime(item.getAnnotatedAt());

        return BookNoteV2Entity.builder()
                .book(book)
                .user(user)
                .cfi(item.getLocation())
                .selectedText(item.getSelectedText())
                .noteContent(getIncomingNoteContent(item))
                .chapterTitle(item.getChapterTitle())
                .color(item.getColor() == null ? "#FFC107" : item.getColor())
                .createdAt(annotatedAt)
                .updatedAt(annotatedAt)
                .build();
    }

    private BookMarkEntity buildBookmark(BookEntity book, BookLoreUserEntity user, KoreaderAnnotationSyncItem item) {
        LocalDateTime annotatedAt = toLocalDateTime(item.getAnnotatedAt());

        return BookMarkEntity.builder()
                .book(book)
                .user(user)
                .cfi(item.getLocation())
                .pageNumber(parsePageNumber(item.getPage()))
                .title(getBookmarkTitle(item))
                .color(item.getColor())
                .notes(getBookmarkNotes(item))
                .priority(bookmarkProperties.getDefaultPriority())
                .createdAt(annotatedAt)
                .updatedAt(annotatedAt)
                .build();
    }

    private void applyAnnotationUpdate(AnnotationEntity existing, KoreaderAnnotationSyncItem item, String device) {
        existing.setCfi(item.getLocation());
        existing.setText(item.getSelectedText());
        existing.setNote(item.getNoteContent());
        existing.setChapterTitle(item.getChapterTitle());
        existing.setColor(item.getColor() == null ? "#FFFF00" : item.getColor());
        existing.setStyle("highlight");

        LocalDateTime annotatedAt = toLocalDateTime(item.getAnnotatedAt());
        if (annotatedAt != null) {
            existing.setUpdatedAt(annotatedAt);
        }
    }

    private void applyNoteUpdate(BookNoteV2Entity existing, KoreaderAnnotationSyncItem item) {
        existing.setCfi(item.getLocation());
        existing.setSelectedText(item.getSelectedText());
        existing.setNoteContent(getIncomingNoteContent(item));
        existing.setChapterTitle(item.getChapterTitle());
        existing.setColor(item.getColor() == null ? "#FFC107" : item.getColor());

        LocalDateTime annotatedAt = toLocalDateTime(item.getAnnotatedAt());
        if (annotatedAt != null) {
            existing.setUpdatedAt(annotatedAt);
        }
    }

    private void applyBookmarkUpdate(BookMarkEntity existing, KoreaderAnnotationSyncItem item) {
        existing.setCfi(item.getLocation());
        existing.setPageNumber(parsePageNumber(item.getPage()));
        existing.setTitle(getBookmarkTitle(item));
        existing.setColor(item.getColor());
        existing.setNotes(getBookmarkNotes(item));
        if (existing.getPriority() == null) {
            existing.setPriority(bookmarkProperties.getDefaultPriority());
        }

        LocalDateTime annotatedAt = toLocalDateTime(item.getAnnotatedAt());
        if (annotatedAt != null) {
            existing.setUpdatedAt(annotatedAt);
        }
    }

    private boolean hasChanges(AnnotationEntity existing, KoreaderAnnotationSyncItem item, String device) {
        String incomingColor = item.getColor() == null ? "#FFFF00" : item.getColor();

        return !Objects.equals(existing.getCfi(), item.getLocation())
                || !Objects.equals(existing.getText(), item.getSelectedText())
                || !Objects.equals(existing.getNote(), item.getNoteContent())
                || !Objects.equals(existing.getChapterTitle(), item.getChapterTitle())
                || !Objects.equals(existing.getColor(), incomingColor)
                || !Objects.equals(existing.getStyle(), "highlight");
    }

    private boolean hasChanges(BookNoteV2Entity existing, KoreaderAnnotationSyncItem item) {
        String incomingColor = item.getColor() == null ? "#FFC107" : item.getColor();

        return !Objects.equals(existing.getCfi(), item.getLocation())
                || !Objects.equals(existing.getSelectedText(), item.getSelectedText())
                || !Objects.equals(existing.getNoteContent(), getIncomingNoteContent(item))
                || !Objects.equals(existing.getChapterTitle(), item.getChapterTitle())
                || !Objects.equals(existing.getColor(), incomingColor);
    }

    private boolean hasChanges(BookMarkEntity existing, KoreaderAnnotationSyncItem item) {
        return !Objects.equals(existing.getCfi(), item.getLocation())
                || !Objects.equals(existing.getPageNumber(), parsePageNumber(item.getPage()))
                || !Objects.equals(existing.getTitle(), getBookmarkTitle(item))
                || !Objects.equals(existing.getColor(), item.getColor())
                || !Objects.equals(existing.getNotes(), getBookmarkNotes(item));
    }

    private SyncResult syncAnnotationItem(BookEntity book, BookLoreUserEntity user, Long userId,
            KoreaderAnnotationSyncItem item, String device) {
        // Entries synced before CFI conversion existed are stored under the raw KOReader
        // position; newer ones under the converted CFI. Look up both so old entries are
        // migrated in place instead of being duplicated.
        String rawLocation = item.getLocation();
        String cfi = convertLocationToCfi(book, item);

        AnnotationEntity existingAnnotation = annotationRepository
                .findByCfiAndBookIdAndUserId(rawLocation, book.getId(), userId)
                .orElse(null);
        BookNoteV2Entity existingNote = bookNoteV2Repository
                .findByCfiAndBookIdAndUserId(rawLocation, book.getId(), userId)
                .orElse(null);
        BookMarkEntity existingBookmark = bookMarkRepository
                .findByCfiAndBookIdAndUserId(rawLocation, book.getId(), userId)
                .orElse(null);

        if (cfi != null) {
            if (existingAnnotation == null) {
                existingAnnotation = annotationRepository
                        .findByCfiAndBookIdAndUserId(cfi, book.getId(), userId).orElse(null);
            }
            if (existingNote == null) {
                existingNote = bookNoteV2Repository
                        .findByCfiAndBookIdAndUserId(cfi, book.getId(), userId).orElse(null);
            }
            if (existingBookmark == null) {
                existingBookmark = bookMarkRepository
                        .findByCfiAndBookIdAndUserId(cfi, book.getId(), userId).orElse(null);
            }
            item.setLocation(cfi);
        }

        boolean hadExistingEntry = existingAnnotation != null || existingNote != null || existingBookmark != null;
        SyncKind kind = determineSyncKind(item);

        return switch (kind) {
            case NOTE ->
                syncNote(book, user, item, existingAnnotation, existingNote, existingBookmark, hadExistingEntry);
            case BOOKMARK -> syncBookmark(book, user, item, existingAnnotation, existingNote, existingBookmark,
                    hadExistingEntry);
            case HIGHLIGHT -> syncHighlight(book, user, item, device, existingAnnotation, existingNote,
                    existingBookmark, hadExistingEntry);
        };
    }

    /**
     * Converts a KOReader xpointer position into an EPUB CFI so the entry can be rendered
     * and opened in the web reader. Returns null when the location is not an xpointer, the
     * book is not an EPUB, or the conversion fails (the raw location is stored then).
     */
    private String convertLocationToCfi(BookEntity book, KoreaderAnnotationSyncItem item) {
        String location = item.getLocation();
        if (location == null || !location.startsWith("/body")) {
            return null;
        }

        try {
            BookFileEntity primaryFile = book.getPrimaryBookFile();
            if (primaryFile == null || primaryFile.getBookType() != BookFileType.EPUB) {
                return null;
            }

            String endLocation = item.getEndLocation();
            String cfi = null;
            if (endLocation != null && endLocation.startsWith("/body") && determineSyncKind(item) != SyncKind.BOOKMARK) {
                try {
                    cfi = epubCfiService.convertXPointerRangeToCfi(primaryFile.getFullFilePath(), location, endLocation);
                } catch (Exception e) {
                    log.debug("Range CFI conversion failed, falling back to start position: {}", e.getMessage());
                }
            }
            if (cfi == null) {
                cfi = epubCfiService.convertXPointerToCfi(primaryFile.getFullFilePath(), location);
            }
            return cfi == null || cfi.isBlank() || cfi.length() > 1000 ? null : cfi;
        } catch (Exception e) {
            log.debug("Failed to convert KOReader location to CFI: {}", e.getMessage());
            return null;
        }
    }

    private SyncResult syncHighlight(BookEntity book, BookLoreUserEntity user, KoreaderAnnotationSyncItem item,
            String device, AnnotationEntity existingAnnotation, BookNoteV2Entity existingNote,
            BookMarkEntity existingBookmark, boolean hadExistingEntry) {
        boolean cleaned = deleteOtherKinds(existingNote, existingBookmark);

        if (existingAnnotation == null) {
            annotationRepository.save(buildAnnotation(book, user, item, device));
            return hadExistingEntry ? SyncResult.UPDATED : SyncResult.CREATED;
        }

        if (hasChanges(existingAnnotation, item, device)) {
            applyAnnotationUpdate(existingAnnotation, item, device);
            annotationRepository.save(existingAnnotation);
            return SyncResult.UPDATED;
        }

        return cleaned ? SyncResult.UPDATED : SyncResult.SKIPPED;
    }

    private SyncResult syncNote(BookEntity book, BookLoreUserEntity user, KoreaderAnnotationSyncItem item,
            AnnotationEntity existingAnnotation, BookNoteV2Entity existingNote, BookMarkEntity existingBookmark,
            boolean hadExistingEntry) {
        boolean cleaned = deleteOtherKinds(existingAnnotation, existingBookmark);

        if (existingNote == null) {
            bookNoteV2Repository.save(buildNote(book, user, item));
            return hadExistingEntry ? SyncResult.UPDATED : SyncResult.CREATED;
        }

        if (hasChanges(existingNote, item)) {
            applyNoteUpdate(existingNote, item);
            bookNoteV2Repository.save(existingNote);
            return SyncResult.UPDATED;
        }

        return cleaned ? SyncResult.UPDATED : SyncResult.SKIPPED;
    }

    private SyncResult syncBookmark(BookEntity book, BookLoreUserEntity user, KoreaderAnnotationSyncItem item,
            AnnotationEntity existingAnnotation, BookNoteV2Entity existingNote, BookMarkEntity existingBookmark,
            boolean hadExistingEntry) {
        boolean cleaned = deleteOtherKinds(existingAnnotation, existingNote);

        if (existingBookmark == null) {
            bookMarkRepository.save(buildBookmark(book, user, item));
            return hadExistingEntry ? SyncResult.UPDATED : SyncResult.CREATED;
        }

        if (hasChanges(existingBookmark, item)) {
            applyBookmarkUpdate(existingBookmark, item);
            bookMarkRepository.save(existingBookmark);
            return SyncResult.UPDATED;
        }

        return cleaned ? SyncResult.UPDATED : SyncResult.SKIPPED;
    }

    private boolean deleteOtherKinds(AnnotationEntity annotation, BookNoteV2Entity note) {
        boolean deleted = false;

        if (annotation != null) {
            annotationRepository.delete(annotation);
            deleted = true;
        }

        if (note != null) {
            bookNoteV2Repository.delete(note);
            deleted = true;
        }

        return deleted;
    }

    private boolean deleteOtherKinds(AnnotationEntity annotation, BookMarkEntity bookmark) {
        boolean deleted = false;

        if (annotation != null) {
            annotationRepository.delete(annotation);
            deleted = true;
        }

        if (bookmark != null) {
            bookMarkRepository.delete(bookmark);
            deleted = true;
        }

        return deleted;
    }

    private boolean deleteOtherKinds(BookNoteV2Entity note, BookMarkEntity bookmark) {
        boolean deleted = false;

        if (note != null) {
            bookNoteV2Repository.delete(note);
            deleted = true;
        }

        if (bookmark != null) {
            bookMarkRepository.delete(bookmark);
            deleted = true;
        }

        return deleted;
    }

    private SyncKind determineSyncKind(KoreaderAnnotationSyncItem item) {
        String externalId = item.getExternalId();
        if (externalId != null) {
            if (externalId.startsWith("bookmark::")) {
                return SyncKind.BOOKMARK;
            }
            if (externalId.startsWith("note::")) {
                return SyncKind.NOTE;
            }
            if (externalId.startsWith("highlight::")) {
                return SyncKind.HIGHLIGHT;
            }
        }

        if (item.getSelectedText() != null && !item.getSelectedText().isBlank()) {
            return SyncKind.HIGHLIGHT;
        }

        return item.getNoteContent() != null && !item.getNoteContent().isBlank()
                ? SyncKind.NOTE
                : SyncKind.HIGHLIGHT;
    }

    private String getIncomingNoteContent(KoreaderAnnotationSyncItem item) {
        if (item.getNoteContent() != null && !item.getNoteContent().isBlank()) {
            return item.getNoteContent();
        }
        return item.getSelectedText();
    }

    private String getBookmarkTitle(KoreaderAnnotationSyncItem item) {
        if (item.getChapterTitle() != null && !item.getChapterTitle().isBlank()) {
            return item.getChapterTitle();
        }
        if (item.getSelectedText() != null && !item.getSelectedText().isBlank()) {
            return item.getSelectedText();
        }
        return "Bookmark";
    }

    private String getBookmarkNotes(KoreaderAnnotationSyncItem item) {
        if (item.getNoteContent() == null || item.getNoteContent().isBlank()) {
            return null;
        }
        return item.getNoteContent();
    }

    private Integer parsePageNumber(String page) {
        if (page == null || page.isBlank()) {
            return null;
        }

        try {
            return Integer.valueOf(page);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private LocalDateTime toLocalDateTime(Long epochSeconds) {
        if (epochSeconds == null) {
            return null;
        }

        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault());
    }

    private KoreaderUserDetails getAuthDetails() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof KoreaderUserDetails details)) {
            log.warn("Authentication failed: invalid principal type");
            throw ApiError.GENERIC_UNAUTHORIZED.createException("User not authenticated");
        }
        return details;
    }

    private KoreaderUserDetails getAuthDetailsWithSyncCheck() {
        KoreaderUserDetails authDetails = getAuthDetails();
        ensureSyncEnabled(authDetails);
        return authDetails;
    }

    private KoreaderUserEntity findKoreaderUser(String username) {
        return koreaderUserRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.warn("KOReader user '{}' not found", username);
                    return ApiError.GENERIC_NOT_FOUND.createException("KOReader user not found");
                });
    }

    private void validatePassword(KoreaderUserEntity koreaderUser, KoreaderUserDetails authDetails) {
        if (koreaderUser.getPasswordMD5() == null ||
                !koreaderUser.getPasswordMD5().equalsIgnoreCase(authDetails.getPassword())) {
            log.warn("Password mismatch for user '{}'", authDetails.getUsername());
            throw ApiError.GENERIC_UNAUTHORIZED.createException("Invalid credentials");
        }
    }

    private BookEntity findBookByHash(String bookHash) {
        return bookRepository.findByCurrentHash(bookHash)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Book not found for hash " + bookHash));
    }

    private BookLoreUserEntity findBookLoreUser(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("User not found with id " + userId));
    }

    private UserBookProgressEntity findUserProgress(long userId, Long bookId) {
        return progressRepository.findByUserIdAndBookId(userId, bookId)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("No progress found for user and book"));
    }

    private UserBookProgressEntity getOrCreateUserProgress(BookLoreUserEntity user, BookEntity book) {
        return progressRepository.findByUserIdAndBookId(user.getId(), book.getId())
                .orElseGet(() -> {
                    UserBookProgressEntity newProgress = new UserBookProgressEntity();
                    newProgress.setUser(user);
                    newProgress.setBook(book);
                    return newProgress;
                });
    }

    private void ensureSyncEnabled(KoreaderUserDetails details) {
        if (!details.isSyncEnabled()) {
            log.warn("Sync is disabled for user '{}'", details.getUsername());
            throw ApiError.GENERIC_UNAUTHORIZED.createException("Sync is disabled for this user");
        }
    }

    private enum SyncKind {
        HIGHLIGHT,
        NOTE,
        BOOKMARK
    }

    private enum SyncResult {
        CREATED,
        UPDATED,
        SKIPPED
    }
}
