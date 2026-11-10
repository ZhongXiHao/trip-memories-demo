package com.cshlands.api.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record CommandParseRequest(
        @NotBlank @Size(max = 500, message = "Input must be at most 500 characters long") String input
) {
}
