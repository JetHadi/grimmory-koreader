package org.booklore.model.dto.koreader;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KoreaderAnnotationSyncRequest {

    @NotBlank(message = "document is required")
    @JsonAlias({ "bookHash" })
    private String document;

    private String device;

    @Valid
    @NotEmpty(message = "annotations must not be empty")
    private List<KoreaderAnnotationSyncItem> annotations;
}
