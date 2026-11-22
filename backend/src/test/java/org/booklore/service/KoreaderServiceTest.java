package org.booklore.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.booklore.config.security.userdetails.KoreaderUserDetails;
import org.booklore.exception.APIException;
import org.booklore.model.dto.koreader.KoreaderAnnotationSyncItem;
import org.booklore.model.dto.koreader.KoreaderAnnotationSyncRequest;
import org.booklore.model.dto.progress.KoreaderProgress;
import org.booklore.model.entity.AnnotationEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.KoreaderUserEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ReadStatus;
import org.booklore.config.BookmarkProperties;
import org.booklore.repository.AnnotationRepository;
import org.booklore.repository.BookMarkRepository;
import org.booklore.repository.BookNoteV2Repository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.UserBookFileProgressRepository;
import org.booklore.repository.UserBookProgressRepository;
import org.booklore.repository.UserRepository;
import org.booklore.repository.KoreaderUserRepository;
import org.booklore.service.hardcover.HardcoverSyncService;
import org.booklore.service.koreader.KoreaderService;
import org.booklore.util.koreader.EpubCfiService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KoreaderServiceTest {

    @Mock
    UserBookProgressRepository progressRepo;
    @Mock
    UserBookFileProgressRepository fileProgressRepo;
    @Mock
    AnnotationRepository annotationRepository;
    @Mock
    BookNoteV2Repository bookNoteV2Repository;
    @Mock
    BookMarkRepository bookMarkRepository;
    @Mock
    BookmarkProperties bookmarkProperties;
    @Mock
    BookRepository bookRepo;
    @Mock
    UserRepository userRepo;
    @Mock
    KoreaderUserRepository koreaderUserRepo;
    @Mock
    HardcoverSyncService hardcoverSyncService;
    @Mock
    EpubCfiService epubCfiService;

    @InjectMocks
    KoreaderService service;

    private KoreaderUserDetails details;

    @BeforeEach
    void setUpAuth() {
        details = mock(KoreaderUserDetails.class);
        when(details.getUsername()).thenReturn("u");
        when(details.getPassword()).thenReturn("md5pwd");
        when(details.getBookLoreUserId()).thenReturn(42L);
        Authentication auth = mock(Authentication.class);
        SecurityContext context = new SecurityContextImpl();
        when(auth.getPrincipal()).thenReturn(details);
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authorizeUser_success() {
        var userEntity = new KoreaderUserEntity();
        userEntity.setPasswordMD5("MD5PWD");
        when(koreaderUserRepo.findByUsername("u"))
                .thenReturn(Optional.of(userEntity));
        when(details.getPassword()).thenReturn("MD5PWD");

        ResponseEntity<Map<String, String>> resp = service.authorizeUser();
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("u", resp.getBody().get("username"));
    }

    @Test
    void authorizeUser_notFound() {
        when(koreaderUserRepo.findByUsername("u")).thenReturn(Optional.empty());
        APIException ex = assertThrows(APIException.class, () -> service.authorizeUser());
        assertTrue(ex.getStatus().is4xxClientError());
    }

    @Test
    void authorizeUser_badPassword() {
        var userEntity = new KoreaderUserEntity();
        userEntity.setPasswordMD5("OTHER");
        when(koreaderUserRepo.findByUsername("u"))
                .thenReturn(Optional.of(userEntity));
        assertThrows(APIException.class, () -> service.authorizeUser());
    }

    @Test
    void getProgress_success() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(99L);
        when(bookRepo.findByCurrentHash("h")).thenReturn(Optional.of(book));
        var prog = new UserBookProgressEntity();
        prog.setKoreaderProgress("p");
        prog.setKoreaderProgressPercent(0.5F);
        when(progressRepo.findByUserIdAndBookId(42L, 99L))
                .thenReturn(Optional.of(prog));

        KoreaderProgress out = service.getProgress("h");
        assertEquals("h", out.getDocument());
        assertEquals("p", out.getProgress());
        assertEquals(0.5F, out.getPercentage());
    }

    @Test
    void getProgress_includesDefaultDeviceId() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(99L);
        when(bookRepo.findByCurrentHash("h")).thenReturn(Optional.of(book));
        var prog = new UserBookProgressEntity();
        prog.setKoreaderProgress("p");
        prog.setKoreaderProgressPercent(0.5F);
        when(progressRepo.findByUserIdAndBookId(42L, 99L))
                .thenReturn(Optional.of(prog));

        KoreaderProgress out = service.getProgress("h");
        assertEquals("Grimmory", out.getDevice());
        assertEquals("Grimmory", out.getDevice_id());
    }

    @Test
    void getProgress_includesSpecifiedDeviceId() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(99L);
        when(bookRepo.findByCurrentHash("h")).thenReturn(Optional.of(book));
        var prog = new UserBookProgressEntity();
        prog.setKoreaderProgress("p");
        prog.setKoreaderProgressPercent(0.5F);
        prog.setKoreaderDevice("Example Device");
        prog.setKoreaderDeviceId("Example Device ID");
        when(progressRepo.findByUserIdAndBookId(42L, 99L))
                .thenReturn(Optional.of(prog));

        KoreaderProgress out = service.getProgress("h");
        assertEquals("Example Device", out.getDevice());
        assertEquals("Example Device ID", out.getDevice_id());
    }

    @Test
    void getProgress_includesOnlyPartialDeviceInfo() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(99L);
        when(bookRepo.findByCurrentHash("h")).thenReturn(Optional.of(book));
        var prog = new UserBookProgressEntity();
        prog.setKoreaderProgress("p");
        prog.setKoreaderProgressPercent(0.5F);
        prog.setKoreaderDevice("Example Device");
        prog.setKoreaderDeviceId(null);
        when(progressRepo.findByUserIdAndBookId(42L, 99L))
                .thenReturn(Optional.of(prog));

        KoreaderProgress out_a = service.getProgress("h");
        assertEquals("Example Device", out_a.getDevice());
        assertEquals("Grimmory", out_a.getDevice_id());

        prog.setKoreaderDevice(null);
        prog.setKoreaderDeviceId("Example Device ID");
        when(progressRepo.findByUserIdAndBookId(42L, 99L))
                .thenReturn(Optional.of(prog));

        KoreaderProgress out_b = service.getProgress("h");
        assertEquals("Grimmory", out_b.getDevice());
        assertEquals("Example Device ID", out_b.getDevice_id());
    }

    @Test
    void getProgress_bookNotFound() {
        when(details.isSyncEnabled()).thenReturn(true);
        when(bookRepo.findByCurrentHash("h")).thenReturn(Optional.empty());
        assertThrows(APIException.class, () -> service.getProgress("h"));
    }

    @Test
    void getProgress_noProgress() {
        when(details.isSyncEnabled()).thenReturn(true);
        when(bookRepo.findByCurrentHash("h"))
                .thenReturn(Optional.of(new BookEntity()));
        when(progressRepo.findByUserIdAndBookId(anyLong(), isNull()))
                .thenReturn(Optional.empty());
        assertThrows(APIException.class, () -> service.getProgress("h"));
    }

    @Test
    void getProgress_syncDisabled() {
        when(details.isSyncEnabled()).thenReturn(false);
        assertThrows(APIException.class, () -> service.getProgress("h"));
    }

    @Test
    void getProgress_includesTimestamp() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(100L);
        when(bookRepo.findByCurrentHash("hash123")).thenReturn(Optional.of(book));

        var prog = new UserBookProgressEntity();
        prog.setKoreaderProgress("progress/path");
        prog.setKoreaderProgressPercent(0.75F);
        Instant syncTime = Instant.ofEpochSecond(1762209924L);
        prog.setKoreaderLastSyncTime(syncTime);
        when(progressRepo.findByUserIdAndBookId(42L, 100L))
                .thenReturn(Optional.of(prog));

        KoreaderProgress out = service.getProgress("hash123");
        assertEquals("hash123", out.getDocument());
        assertEquals("progress/path", out.getProgress());
        assertEquals(0.75F, out.getPercentage());
        assertEquals(1762209924L, out.getTimestamp());
    }

    @Test
    void getProgress_nullTimestamp() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(101L);
        when(bookRepo.findByCurrentHash("hash456")).thenReturn(Optional.of(book));

        var prog = new UserBookProgressEntity();
        prog.setKoreaderProgress("progress/path2");
        prog.setKoreaderProgressPercent(0.25F);
        prog.setKoreaderLastSyncTime(null);
        when(progressRepo.findByUserIdAndBookId(42L, 101L))
                .thenReturn(Optional.of(prog));

        KoreaderProgress out = service.getProgress("hash456");
        assertEquals("hash456", out.getDocument());
        assertEquals("progress/path2", out.getProgress());
        assertEquals(0.25F, out.getPercentage());
        assertNull(out.getTimestamp());
    }

    @Test
    void saveProgress_createsNew() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(7L);
        when(bookRepo.findByCurrentHash("h")).thenReturn(Optional.of(book));
        var user = new BookLoreUserEntity();
        user.setId(42L);
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));
        when(progressRepo.findByUserIdAndBookId(42L, 7L))
                .thenReturn(Optional.empty());

        var dto = KoreaderProgress.builder()
                .document("h").progress("x").percentage(0.6F).device("d").device_id("id").build();
        service.saveProgress("h", dto);

        ArgumentCaptor<UserBookProgressEntity> cap = ArgumentCaptor.forClass(UserBookProgressEntity.class);
        verify(progressRepo).save(cap.capture());
        var saved = cap.getValue();
        assertEquals("x", saved.getKoreaderProgress());
        assertEquals(0.6F, saved.getKoreaderProgressPercent());
        assertEquals("d", saved.getKoreaderDevice());
        assertEquals("id", saved.getKoreaderDeviceId());
        assertEquals(Instant.class, saved.getKoreaderLastSyncTime().getClass());
    }

    @Test
    void saveProgress_updatesExisting() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(8L);
        when(bookRepo.findByCurrentHash("h")).thenReturn(Optional.of(book));
        var user = new BookLoreUserEntity();
        user.setId(42L);
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));
        var existing = new UserBookProgressEntity();
        when(progressRepo.findByUserIdAndBookId(42L, 8L))
                .thenReturn(Optional.of(existing));

        var dto = KoreaderProgress.builder()
                .document("h").progress("y").percentage(0.4F).device("d").device_id("id").build();
        service.saveProgress("h", dto);

        verify(progressRepo).save(existing);
        assertEquals("y", existing.getKoreaderProgress());
        assertEquals(0.4F, existing.getKoreaderProgressPercent());
    }

    @Test
    void saveProgress_updatesExistingNoProgressChange_noHardcoverUpdate() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(8L);
        when(bookRepo.findByCurrentHash("h")).thenReturn(Optional.of(book));
        var user = new BookLoreUserEntity();
        user.setId(42L);
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));
        var existing = new UserBookProgressEntity();
        existing.setKoreaderProgressPercent(0.4F);
        existing.setReadStatus(ReadStatus.READING);
        when(progressRepo.findByUserIdAndBookId(42L, 8L))
                .thenReturn(Optional.of(existing));

        var dto = KoreaderProgress.builder()
                .document("h").progress("y").percentage(0.4F).device("d").device_id("id").build();
        service.saveProgress("h", dto);

        verify(progressRepo).save(existing);
        assertEquals("y", existing.getKoreaderProgress());
        assertEquals(0.4F, existing.getKoreaderProgressPercent());
        verify(hardcoverSyncService, never()).syncProgressToHardcover(any(), any(), any());
    }

    @Test
    void saveProgress_syncDisabled() {
        when(details.isSyncEnabled()).thenReturn(false);
        var dto = KoreaderProgress.builder().document("h").build();
        assertThrows(APIException.class, () -> service.saveProgress("h", dto));
    }

    @Test
    void syncProgressToKoreader_whenEnabled_updatesPercentAndXPointer() {
        BookEntity book = epubBook(11L);
        BookLoreUserEntity user = user(42L);
        UserBookProgressEntity progress = new UserBookProgressEntity();
        progress.setEpubProgress("epubcfi(/6/8!/4/2/6)");
        when(koreaderUserRepo.findByBookLoreUserId(42L)).thenReturn(Optional.of(koreaderUser(true)));
        when(bookRepo.findById(11L)).thenReturn(Optional.of(book));
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));
        when(progressRepo.findByUserIdAndBookId(42L, 11L)).thenReturn(Optional.of(progress));
        when(epubCfiService.convertCfiToProgressXPointer(any(Path.class), eq("epubcfi(/6/8!/4/2/6)")))
                .thenReturn("/6/8!/4/2/6/1:15");

        service.syncProgressToKoreader(11L, 75f, 42L);

        assertEquals(0.75f, progress.getKoreaderProgressPercent());
        assertEquals("/6/8!/4/2/6/1:15", progress.getKoreaderProgress());
        assertNotNull(progress.getKoreaderLastSyncTime());
        verify(progressRepo).save(progress);
        verify(epubCfiService).convertCfiToProgressXPointer(any(Path.class), eq("epubcfi(/6/8!/4/2/6)"));
    }

    @Test
    void syncProgressToKoreader_whenConversionFails_clearsStaleXPointerButKeepsPercent() {
        BookEntity book = epubBook(11L);
        BookLoreUserEntity user = user(42L);
        UserBookProgressEntity progress = new UserBookProgressEntity();
        progress.setEpubProgress("epubcfi(/6/8!/4/2/6)");
        progress.setKoreaderProgress("/old/xpointer");
        when(koreaderUserRepo.findByBookLoreUserId(42L)).thenReturn(Optional.of(koreaderUser(true)));
        when(bookRepo.findById(11L)).thenReturn(Optional.of(book));
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));
        when(progressRepo.findByUserIdAndBookId(42L, 11L)).thenReturn(Optional.of(progress));
        when(epubCfiService.convertCfiToProgressXPointer(any(Path.class), eq("epubcfi(/6/8!/4/2/6)")))
                .thenThrow(new RuntimeException("conversion failed"));

        service.syncProgressToKoreader(11L, 100f, 42L);

        assertEquals(1.0f, progress.getKoreaderProgressPercent());
        assertNull(progress.getKoreaderProgress());
        assertNotNull(progress.getKoreaderLastSyncTime());
        verify(progressRepo).save(progress);
        verify(epubCfiService).convertCfiToProgressXPointer(any(Path.class), eq("epubcfi(/6/8!/4/2/6)"));
    }

    @Test
    void syncProgressToKoreader_whenGlobalSyncDisabled_skipsUpdate() {
        when(koreaderUserRepo.findByBookLoreUserId(42L)).thenReturn(Optional.of(koreaderUser(false)));

        service.syncProgressToKoreader(11L, 75f, 42L);

        verifyNoInteractions(bookRepo, userRepo, epubCfiService);
        verify(progressRepo, never()).save(any());
    }

    @Test
    void syncProgressToKoreader_whenBookOrUserMissing_logsAndSkipsSave() {
        when(koreaderUserRepo.findByBookLoreUserId(42L)).thenReturn(Optional.of(koreaderUser(true)));
        when(bookRepo.findById(11L)).thenReturn(Optional.empty());
        when(userRepo.findById(42L)).thenReturn(Optional.of(user(42L)));

        assertDoesNotThrow(() -> service.syncProgressToKoreader(11L, 75f, 42L));

        verify(progressRepo, never()).save(any());
    }

    @Test
    void syncProgressToKoreader_whenNotEpub_clearsStaleXPointer() {
        BookEntity book = bookWithPrimaryFile(11L, BookFileType.PDF);
        BookLoreUserEntity user = user(42L);
        UserBookProgressEntity progress = new UserBookProgressEntity();
        progress.setEpubProgress("epubcfi(/6/8!/4/2/6)");
        progress.setKoreaderProgress("/old/xpointer");
        when(koreaderUserRepo.findByBookLoreUserId(42L)).thenReturn(Optional.of(koreaderUser(true)));
        when(bookRepo.findById(11L)).thenReturn(Optional.of(book));
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));
        when(progressRepo.findByUserIdAndBookId(42L, 11L)).thenReturn(Optional.of(progress));

        service.syncProgressToKoreader(11L, 50f, 42L);

        assertEquals(0.5f, progress.getKoreaderProgressPercent());
        assertNull(progress.getKoreaderProgress());
        assertNotNull(progress.getKoreaderLastSyncTime());
        verifyNoInteractions(epubCfiService);
        verify(progressRepo).save(progress);
    }

    @Test
    void syncProgressToKoreader_usesGrimmoryDeviceInfo() {
        BookEntity book = bookWithPrimaryFile(11L, BookFileType.PDF);
        BookLoreUserEntity user = user(42L);
        UserBookProgressEntity progress = new UserBookProgressEntity();
        progress.setKoreaderDeviceId("EXAMPLE-ID");
        progress.setKoreaderDevice("EXAMPLE-DEVICE");
        when(koreaderUserRepo.findByBookLoreUserId(42L)).thenReturn(Optional.of(koreaderUser(true)));
        when(bookRepo.findById(11L)).thenReturn(Optional.of(book));
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));
        when(progressRepo.findByUserIdAndBookId(42L, 11L)).thenReturn(Optional.of(progress));

        service.syncProgressToKoreader(11L, 50f, 42L);

        assertEquals("Grimmory", progress.getKoreaderDevice());
        assertEquals("Grimmory", progress.getKoreaderDeviceId());
        verify(progressRepo).save(progress);
    }

    @Test
    void normalizeProgressPercent_handlesNullAndRanges() throws Exception {
        Method method = KoreaderService.class.getDeclaredMethod("normalizeProgressPercent", Float.class);
        method.setAccessible(true);

        assertNull(method.invoke(service, new Object[] { null }));
        assertEquals(50.0f, (Float) method.invoke(service, 0.5f));
        assertEquals(100.0f, (Float) method.invoke(service, 1.0f));
        assertEquals(42.0f, (Float) method.invoke(service, 42.0f));
    }

    @Test
    void syncAnnotations_createsNewEntries() {
        when(details.isSyncEnabled()).thenReturn(true);

        var book = new BookEntity();
        book.setId(77L);
        when(bookRepo.findByCurrentHash("hash")).thenReturn(Optional.of(book));

        var user = new BookLoreUserEntity();
        user.setId(42L);
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));

        var item = KoreaderAnnotationSyncItem.builder()
                .location("epubcfi(/6/2)")
                .selectedText("quoted")
                .noteContent("note")
                .chapterTitle("Chapter 1")
                .color("#FFFF00")
                .build();
        var request = KoreaderAnnotationSyncRequest.builder()
                .document("hash")
                .device("KOReader")
                .annotations(List.of(item))
                .build();

        when(annotationRepository.findByCfiAndBookIdAndUserId("epubcfi(/6/2)", 77L, 42L))
                .thenReturn(Optional.empty());

        var response = service.syncAnnotations(request);

        assertTrue(response.isMatchedBook());
        assertEquals(77L, response.getBookId());
        assertEquals(1, response.getCreated());
        assertEquals(0, response.getUpdated());
        assertEquals(0, response.getSkippedDuplicates());
        assertEquals(0, response.getRejected());
        verify(annotationRepository).save(any(AnnotationEntity.class));
    }

    @Test
    void syncAnnotations_updatesWhenExistingDiffers() {
        when(details.isSyncEnabled()).thenReturn(true);

        var book = new BookEntity();
        book.setId(78L);
        when(bookRepo.findByCurrentHash("hash")).thenReturn(Optional.of(book));

        var user = new BookLoreUserEntity();
        user.setId(42L);
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));

        var existing = new AnnotationEntity();
        existing.setBook(book);
        existing.setUser(user);
        existing.setCfi("loc");
        existing.setText("old");
        existing.setColor("#FFFF00");

        when(annotationRepository.findByCfiAndBookIdAndUserId("loc", 78L, 42L))
                .thenReturn(Optional.of(existing));

        var item = KoreaderAnnotationSyncItem.builder()
                .location("loc")
                .selectedText("new")
                .noteContent("note")
                .build();
        var request = KoreaderAnnotationSyncRequest.builder()
                .document("hash")
                .annotations(List.of(item))
                .build();

        var response = service.syncAnnotations(request);

        assertEquals(0, response.getCreated());
        assertEquals(1, response.getUpdated());
        assertEquals(0, response.getSkippedDuplicates());
        verify(annotationRepository).save(eq(existing));
    }

    @Test
    void syncAnnotations_skipsExactDuplicates() {
        when(details.isSyncEnabled()).thenReturn(true);

        var book = new BookEntity();
        book.setId(79L);
        when(bookRepo.findByCurrentHash("hash")).thenReturn(Optional.of(book));

        var user = new BookLoreUserEntity();
        user.setId(42L);
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));

        var existing = new AnnotationEntity();
        existing.setBook(book);
        existing.setUser(user);
        existing.setCfi("loc");
        existing.setText("same");
        existing.setNote("same-note");
        existing.setColor("#FFFF00");
        existing.setStyle("highlight");

        when(annotationRepository.findByCfiAndBookIdAndUserId("loc", 79L, 42L))
                .thenReturn(Optional.of(existing));

        var item = KoreaderAnnotationSyncItem.builder()
                .location("loc")
                .selectedText("same")
                .noteContent("same-note")
                .build();
        var request = KoreaderAnnotationSyncRequest.builder()
                .document("hash")
                .annotations(List.of(item))
                .build();

        var response = service.syncAnnotations(request);

        assertEquals(0, response.getCreated());
        assertEquals(0, response.getUpdated());
        assertEquals(1, response.getSkippedDuplicates());
        verify(annotationRepository, never()).save(any(AnnotationEntity.class));
    }

    @Test
    void syncAnnotations_returnsMatchedBookFalseWhenHashUnknown() {
        when(details.isSyncEnabled()).thenReturn(true);
        when(bookRepo.findByCurrentHash("missing")).thenReturn(Optional.empty());

        var request = KoreaderAnnotationSyncRequest.builder()
                .document("missing")
                .annotations(List.of(
                        KoreaderAnnotationSyncItem.builder().location("a").selectedText("one").build(),
                        KoreaderAnnotationSyncItem.builder().location("b").selectedText("two").build()))
                .build();

        var response = service.syncAnnotations(request);

        assertFalse(response.isMatchedBook());
        assertNull(response.getBookId());
        assertEquals(2, response.getRejected());
        assertEquals("book_not_found_for_hash", response.getRejectionReasons().get(0).getReason());
    }

    private KoreaderUserEntity koreaderUser(boolean syncEnabled) {
        return KoreaderUserEntity.builder()
                .syncEnabled(syncEnabled)
                .build();
    }

    private BookLoreUserEntity user(long userId) {
        BookLoreUserEntity user = new BookLoreUserEntity();
        user.setId(userId);
        return user;
    }

    private BookEntity epubBook(long bookId) {
        return bookWithPrimaryFile(bookId, BookFileType.EPUB);
    }

    private BookEntity bookWithPrimaryFile(long bookId, BookFileType bookFileType) {
        BookEntity book = new BookEntity();
        book.setId(bookId);
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath("/library");
        book.setLibraryPath(libraryPath);

        BookFileEntity primaryFile = new BookFileEntity();
        primaryFile.setId(1L);
        primaryFile.setBook(book);
        primaryFile.setBookType(bookFileType);
        primaryFile.setFileSubPath("subdir");
        primaryFile.setFileName("book.epub");
        book.setBookFiles(Set.of(primaryFile));
        return book;
    }
}
