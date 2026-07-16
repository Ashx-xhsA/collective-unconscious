package com.cu.collective_unconscious.dtos;

import java.util.Set;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CreateTagsRequest {
  @NotEmpty(message = "At least one tag name is required")
  @Size(max = 10, message = "Maximum {max} tags allowed")
  private Set<
          @Size(min = 1, max = 30, message = "Tag name must be between {min} and {max} characters" )
          @Pattern(
                  regexp = "^[\\p{L}\\p{N}\\s_-]+$",
                  message = "Tag name can only contain letters, numbers, spaces, underscores, and hyphens"
          )
                  String> names;
}
