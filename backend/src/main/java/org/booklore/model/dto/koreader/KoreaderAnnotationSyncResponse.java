package org.booklore.model.dto.koreader;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KoreaderAnnotationSyncResponse {
    private Long bookId;
    private boolean matchedBook;
    private String matchedBy;
    private int created;
    private int updated;
    private int skippedDuplicates;
    private int rejected;
    private List<KoreaderAnnotationRejection> rejectionReasons;
}
