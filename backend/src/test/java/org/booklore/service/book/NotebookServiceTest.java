package org.booklore.service.book;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.NotebookEntry;
import org.booklore.repository.NotebookEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotebookServiceTest {

    private NotebookEntryRepository repository;
    private NotebookService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotebookEntryRepository.class);
        AuthenticationService authenticationService = mock(AuthenticationService.class);
        AnnotationService annotationService = mock(AnnotationService.class);
        BookNoteV2Service bookNoteV2Service = mock(BookNoteV2Service.class);
        BookMarkService bookMarkService = mock(BookMarkService.class);
        service = new NotebookService(repository, authenticationService, annotationService, bookNoteV2Service,
                bookMarkService);

        BookLoreUser user = new BookLoreUser();
        user.setId(7L);
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
    }

    @Test
    void getNotebookEntries_mapsBookmarkPageNumber() {
        NotebookEntryRepository.EntryProjection projection = mock(NotebookEntryRepository.EntryProjection.class);
        when(projection.getId()).thenReturn(11L);
        when(projection.getType()).thenReturn("BOOKMARK");
        when(projection.getBookId()).thenReturn(22L);
        when(projection.getBookTitle()).thenReturn("Sample Book");
        when(projection.getText()).thenReturn("Bookmark title");
        when(projection.getNote()).thenReturn("Bookmark note");
        when(projection.getColor()).thenReturn("#f00");
        when(projection.getStyle()).thenReturn(null);
        when(projection.getChapterTitle()).thenReturn(null);
        when(projection.getCfi()).thenReturn("epubcfi(/6/2)");
        when(projection.getPageNumber()).thenReturn(128);
        when(projection.getPrimaryBookType()).thenReturn("PDF");
        when(projection.getCreatedAt()).thenReturn(LocalDateTime.parse("2026-03-26T10:00:00"));
        when(projection.getUpdatedAt()).thenReturn(LocalDateTime.parse("2026-03-26T10:05:00"));

        Page<NotebookEntryRepository.EntryProjection> page = new PageImpl<>(Collections.singletonList(projection));
        when(repository.findEntries(eq(7L), eq(Collections.singleton("BOOKMARK")), isNull(), isNull(), any()))
                .thenReturn(page);

        NotebookEntry result = service.getNotebookEntries(0, 50, Collections.singleton("BOOKMARK"), null, null, "desc")
                .getContent()
                .getFirst();

        assertEquals(128, result.getPageNumber());
        assertEquals("epubcfi(/6/2)", result.getCfi());
        assertEquals("BOOKMARK", result.getType());
        assertEquals("Sample Book", result.getBookTitle());
    }
}