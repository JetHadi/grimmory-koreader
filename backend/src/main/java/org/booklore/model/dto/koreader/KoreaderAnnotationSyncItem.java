package org.booklore.model.dto.koreader;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KoreaderAnnotationSyncItem {

    @NotBlank(message = "location is required")
    @Size(max = 1000, message = "location must not exceed 1000 characters")
    private String location;

    @NotBlank(message = "selectedText is required")
    @Size(max = 5000, message = "selectedText must not exceed 5000 characters")
    private String selectedText;

    @Size(max = 5000, message = "noteContent must not exceed 5000 characters")
    private String noteContent;

    @Size(max = 500, message = "chapterTitle must not exceed 500 characters")
    private String chapterTitle;

    @Size(max = 100, message = "page must not exceed 100 characters")
    private String page;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "color must be a valid hex color (e.g., #FFFF00)")
    private String color;

    private Long annotatedAt;

    @Size(max = 255, message = "externalId must not exceed 255 characters")
    private String externalId;
}
